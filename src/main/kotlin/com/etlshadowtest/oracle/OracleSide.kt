package com.etlshadowtest.oracle

import com.etlshadowtest.api.Location
import com.etlshadowtest.config.OracleProperties
import com.etlshadowtest.run.RunContext
import com.etlshadowtest.target.AggregateQuery
import com.etlshadowtest.target.KeyFingerprintTable
import com.etlshadowtest.target.RowFetch
import com.etlshadowtest.target.TargetReader
import com.etlshadowtest.target.AggregateSpec
import com.etlshadowtest.target.AggregateValues
import com.etlshadowtest.target.BoundScope
import com.etlshadowtest.target.ColumnMeta
import com.etlshadowtest.target.TargetSide
import com.etlshadowtest.target.bindTo
import com.etlshadowtest.target.whereClause
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.duckdb.DuckDBConnection
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.util.concurrent.Semaphore

/** Oracle access for one Environment. */
class OracleSide(override val environment: String, private val props: OracleProperties) : TargetSide, AutoCloseable {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            poolName = "oracle-$environment"
            jdbcUrl = props.replicaUrl ?: props.url
            username = props.username
            password = props.password
            maximumPoolSize = props.maxConnections
            minimumIdle = 0
            initializationFailTimeout = -1
            connectionTimeout = props.connectionTimeout.toMillis()
        },
    )

    private val parallelQueries = Semaphore(minOf(props.maxParallelQueries, props.maxConnections).coerceAtLeast(1))
    private val timeoutSeconds = props.queryTimeout.seconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    /**
     * Runs read-only work on a pooled connection. At most the configured number of queries run at once across all
     * Test Runs, and a query that runs past the time limit is cancelled and reported as [QueryTimeoutException].
     */
    private fun <T> read(block: (Connection) -> T): T {
        parallelQueries.acquire()
        try {
            return pool.connection.use(block)
        } catch (e: SQLTimeoutException) {
            throw QueryTimeoutException(environment, timeoutSeconds, e)
        } catch (e: SQLException) {
            if (e.errorCode == ORA_USER_REQUESTED_CANCEL) throw QueryTimeoutException(environment, timeoutSeconds, e)
            throw e
        } finally {
            parallelQueries.release()
        }
    }

    /** Every statement this service sends is a SELECT. This is the last line of defence behind the read-only account. */
    private fun Connection.select(sql: String): PreparedStatement {
        require(sql.trimStart().startsWith("SELECT", ignoreCase = true)) { "Only SELECT statements may be sent to $environment" }
        return prepareStatement(sql).also { it.queryTimeout = timeoutSeconds }
    }

    /** The table's columns from Oracle's metadata, or null when the table does not exist (or is not visible). */
    override fun columns(location: Location): List<ColumnMeta>? = read { c ->
        c.select(
            "SELECT COLUMN_NAME, DATA_TYPE FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID",
        ).use { ps ->
            ps.setString(1, location.schema)
            ps.setString(2, location.table)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(OracleSql.column(rs.getString(1), rs.getString(2))) }
            }
        }
    }.ifEmpty { null }

    override fun readerFor(ctx: RunContext): TargetReader = object : TargetReader {
        /** The Aggregate Check, computed inside Oracle in one pass over the Comparison Scope. */
        override fun aggregate(location: Location, spec: AggregateSpec, scope: BoundScope?): AggregateValues = read { c ->
            val query = AggregateQuery(spec, OracleSql)
            c.select(query.sql(qualified(location), scope)).use { ps ->
                scope.bindTo(ps, 1)
                ps.executeQuery().use(query::read)
            }
        }

        /** Streams the rows out of Oracle into the Test Run's DuckDB, so the diff itself never runs in Oracle. */
        override fun loadKeyFingerprints(location: Location, into: KeyFingerprintTable, keys: List<ColumnMeta>, fingerprint: List<ColumnMeta>, scope: BoundScope?) {
            val workspace = ctx.workspace()
            into.create(workspace)
            val sql = into.select(OracleSql, keys, fingerprint, qualified(location), scope)
            val columnCount = keys.size + 1
            (workspace.connection as DuckDBConnection).createAppender(DuckDBConnection.DEFAULT_SCHEMA, into.name).use { appender ->
                read { c ->
                    c.select(sql).use { ps ->
                        ps.fetchSize = FETCH_SIZE
                        scope.bindTo(ps, 1)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                appender.beginRow()
                                for (i in 1..columnCount) appender.append(rs.getString(i))
                                appender.endRow()
                            }
                        }
                    }
                }
            }
        }

        override fun fetchRows(location: Location, columns: List<ColumnMeta>, keys: List<ColumnMeta>, keyTuples: List<List<String?>>, scope: BoundScope?) =
            RowFetch(OracleSql).fetch(qualified(location), columns, keys, keyTuples, scope) { sql, bind, readResult ->
                read { c -> c.select(sql).use { ps -> bind(ps); ps.executeQuery().use(readResult) } }
            }
    }

    override fun close() = pool.close()

    companion object {
        private const val FETCH_SIZE = 10_000
        private const val ORA_USER_REQUESTED_CANCEL = 1013

        fun quote(identifier: String): String {
            require(identifier.isNotEmpty() && '"' !in identifier && '\u0000' !in identifier) { "Invalid identifier: $identifier" }
            return "\"$identifier\""
        }

        fun qualified(location: Location) = "${quote(location.schema!!)}.${quote(location.table!!)}"
    }
}

/** A query outran its time limit and was cancelled. */
class QueryTimeoutException(environment: String, seconds: Int, cause: Throwable) :
    RuntimeException("A query against ${environment.replaceFirstChar { it.uppercase() }} exceeded the time limit of ${seconds}s and was cancelled", cause)
