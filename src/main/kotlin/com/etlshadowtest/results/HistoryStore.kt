package com.etlshadowtest.results

import com.etlshadowtest.config.ShadowProperties
import com.etlshadowtest.duckdb.DuckDbMetrics
import com.etlshadowtest.duckdb.DuckS3
import com.etlshadowtest.duckdb.Workspace
import com.etlshadowtest.run.HistoryEntry
import com.etlshadowtest.run.RunStatus
import com.etlshadowtest.run.TargetVerdict
import com.etlshadowtest.run.Verdict
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import org.duckdb.DuckDBConnection
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Answers history queries by having DuckDB scan the Test Run records in MinIO (ADR 0002); no database is involved. */
@Component
class HistoryStore(
    private val props: ShadowProperties,
    private val results: ResultsStore,
    private val mapper: ObjectMapper,
    private val metrics: DuckDbMetrics,
) {
    private var watch: DuckDbMetrics.Watch? = null

    private val root: Connection by lazy {
        DriverManager.getConnection("jdbc:duckdb:").also { c ->
            c.createStatement().use {
                it.execute("SET memory_limit = '256MB'")
                it.execute("SET threads = 2")
                it.execute("SET preserve_insertion_order = false")
            }
            DuckS3.configure(c, "s3_results", props.results.minio, props.duckdb.extensionDirectory)
            watch = metrics.watch(DuckDbMetrics.Role.HISTORY, c)
        }
    }

    @Volatile
    private var started = false

    @PreDestroy
    fun close() {
        watch?.close()
        if (started) root.close()
    }

    @Synchronized
    private fun connection(): Connection {
        started = true
        return (root as DuckDBConnection).duplicate()
    }

    // Records are written as one line of JSON, so newline_delimited applies, and it lets DuckDB skip an unreadable record
    // instead of failing the whole query (one damaged run.json must not blind the history or the abandoned sweep).
    /** [pipeline] must already be a safe name; [from] is inclusive and [to] exclusive, both in the run records' timestamp format. */
    fun list(pipeline: String, from: String?, to: String?, limit: Int): List<HistoryEntry> {
        results.ensureBucket()
        val glob = "s3://${props.results.minio.bucket}/results/$pipeline/*/run.json"
        val sql = "SELECT testRunId, status, startedAt, finishedAt, heartbeatAt, verdict, reason, scope, targets, unverifiedTargets " +
            "FROM read_json(${Workspace.literal(glob)}, format = 'newline_delimited', maximum_object_size = 8388608, ignore_errors = true, columns = {" +
            "testRunId: 'VARCHAR', status: 'VARCHAR', startedAt: 'VARCHAR', finishedAt: 'VARCHAR', heartbeatAt: 'VARCHAR', " +
            "verdict: 'VARCHAR', reason: 'VARCHAR', scope: 'JSON', targets: 'JSON', unverifiedTargets: 'JSON'}) " +
            "WHERE testRunId IS NOT NULL AND status IS NOT NULL AND startedAt IS NOT NULL AND heartbeatAt IS NOT NULL " +
            "AND (?::VARCHAR IS NULL OR startedAt >= ?) AND (?::VARCHAR IS NULL OR startedAt < ?) " +
            "ORDER BY startedAt DESC, testRunId LIMIT ?"
        return try {
            connection().use { c ->
                c.prepareStatement(sql).use { ps ->
                    ps.setString(1, from); ps.setString(2, from)
                    ps.setString(3, to); ps.setString(4, to)
                    ps.setInt(5, limit)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    HistoryEntry(
                                        testRunId = rs.getString(1),
                                        status = RunStatus.valueOf(rs.getString(2)),
                                        startedAt = rs.getString(3),
                                        finishedAt = rs.getString(4),
                                        heartbeatAt = rs.getString(5),
                                        verdict = rs.getString(6)?.let(Verdict::valueOf),
                                        reason = rs.getString(7),
                                        scope = rs.getString(8)?.let { mapper.readValue<com.etlshadowtest.api.ScopeRange>(it) },
                                        targets = rs.getString(9)?.let { targets(it) } ?: emptyList(),
                                        unverifiedTargets = rs.getString(10)?.let { mapper.readValue<List<String>>(it) } ?: emptyList(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            // A Pipeline that has never run has no records at all.
            if (e.message?.contains("No files found") == true) emptyList() else throw e
        }
    }

    /** Test Runs of every Pipeline whose record still says RUNNING, for the sweep that finds abandoned ones. */
    fun listRunning(): List<RunningRef> {
        results.ensureBucket()
        val glob = "s3://${props.results.minio.bucket}/results/*/*/run.json"
        val sql = "SELECT pipeline, testRunId, heartbeatAt FROM read_json(${Workspace.literal(glob)}, format = 'newline_delimited', maximum_object_size = 8388608, ignore_errors = true, " +
            "columns = {pipeline: 'VARCHAR', testRunId: 'VARCHAR', status: 'VARCHAR', heartbeatAt: 'VARCHAR'}) WHERE status = 'RUNNING' AND pipeline IS NOT NULL AND testRunId IS NOT NULL AND heartbeatAt IS NOT NULL"
        return try {
            connection().use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(RunningRef(rs.getString(1), rs.getString(2), rs.getString(3))) } }
                }
            }
        } catch (e: SQLException) {
            if (e.message?.contains("No files found") == true) emptyList() else throw e
        }
    }

    private fun targets(json: String): List<TargetVerdict> =
        mapper.readTree(json).map { TargetVerdict(it["name"].asText(), Verdict.valueOf(it["verdict"].asText())) }
}

data class RunningRef(val pipeline: String, val testRunId: String, val heartbeatAt: String)
