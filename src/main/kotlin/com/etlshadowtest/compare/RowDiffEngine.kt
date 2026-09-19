package com.etlshadowtest.compare

import com.etlshadowtest.api.TargetConfig
import com.etlshadowtest.config.ShadowProperties
import com.etlshadowtest.results.ResultsStore
import com.etlshadowtest.run.CountDifference
import com.etlshadowtest.run.CountDifferencesResult
import com.etlshadowtest.run.DuplicateKey
import com.etlshadowtest.run.DuplicateKeysResult
import com.etlshadowtest.run.DuplicateKeysSide
import com.etlshadowtest.run.RowDiffResult
import com.etlshadowtest.run.RunContext
import com.etlshadowtest.duckdb.Workspace
import com.etlshadowtest.target.BoundScope
import com.etlshadowtest.target.ColumnMeta
import com.etlshadowtest.target.KeyValues
import com.etlshadowtest.target.TargetSide
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * The Row Diff (ADR 0001): key plus Row Fingerprint from each Environment are diffed in the Test Run's DuckDB,
 * then full rows are fetched only for a bounded sample of the keys that differ.
 */
@Component
class RowDiffEngine(private val props: ShadowProperties, private val results: ResultsStore, private val mapper: ObjectMapper) {
    private enum class Type { ONLY_IN_STAGING, ONLY_IN_PRODUCTION, DIFFERENT }

    private class Sample(val type: Type, val key: Map<String, Any?>, val staging: Map<String, Any?>?, val production: Map<String, Any?>?, val differing: List<String>)

    fun run(
        ctx: RunContext,
        target: TargetConfig,
        staging: TargetSide,
        production: TargetSide,
        columns: List<ColumnMeta>,
        keys: List<ColumnMeta>,
        fingerprint: List<ColumnMeta>,
        scope: BoundScope?,
        tolerances: Map<String, BigDecimal> = emptyMap(),
    ): RowDiffResult {
        val ws = ctx.workspace()
        try {
            staging.loadKeyFingerprints(target.staging, keys, fingerprint, scope, ws, "stg")
            production.loadKeyFingerprints(target.production, keys, fingerprint, scope, ws, "prod")
            return if (keys.isEmpty()) diffKeyless(ws) else diffKeyed(ctx, ws, target, staging, production, columns, keys, scope, tolerances)
        } finally {
            for (t in listOf("stg", "prod", "stg_u", "prod_u", "dup_s", "dup_p", "dup_keys", "diff", "cdiff", "sample")) {
                runCatching { ws.execute("DROP TABLE IF EXISTS $t") }
            }
        }
    }

    private fun diffKeyed(
        ctx: RunContext,
        ws: Workspace,
        target: TargetConfig,
        staging: TargetSide,
        production: TargetSide,
        columns: List<ColumnMeta>,
        keys: List<ColumnMeta>,
        scope: BoundScope?,
        tolerances: Map<String, BigDecimal>,
    ): RowDiffResult {
        val keyCols = keys.indices.map { "k$it" }
        val keyList = keyCols.joinToString(", ")
        val limit = props.mismatchSampleSize

        // A key that is not unique cannot be matched, so duplicated keys are reported on their own and left out of the diff.
        ws.execute("CREATE TABLE dup_s AS SELECT $keyList, count(*) AS occurrences FROM stg GROUP BY $keyList HAVING count(*) > 1")
        ws.execute("CREATE TABLE dup_p AS SELECT $keyList, count(*) AS occurrences FROM prod GROUP BY $keyList HAVING count(*) > 1")
        ws.execute("CREATE TABLE dup_keys AS SELECT $keyList FROM dup_s UNION SELECT $keyList FROM dup_p")
        val antiJoin = keyCols.joinToString(" AND ") { "x.$it IS NOT DISTINCT FROM d.$it" }
        ws.execute("CREATE TABLE stg_u AS SELECT x.* FROM stg x ANTI JOIN dup_keys d ON $antiJoin")
        ws.execute("CREATE TABLE prod_u AS SELECT x.* FROM prod x ANTI JOIN dup_keys d ON $antiJoin")

        val join = keyCols.joinToString(" AND ") { "s.$it IS NOT DISTINCT FROM p.$it" }
        ws.execute(
            "CREATE TABLE diff AS SELECT ${keyCols.joinToString(", ") { "coalesce(s.$it, p.$it) AS $it" }}, " +
                "CASE WHEN p.fp IS NULL THEN 'ONLY_IN_STAGING' WHEN s.fp IS NULL THEN 'ONLY_IN_PRODUCTION' ELSE 'DIFFERENT' END AS type " +
                "FROM stg_u s FULL OUTER JOIN prod_u p ON $join WHERE s.fp IS DISTINCT FROM p.fp",
        )
        val counts = counts(ws)
        val sampleKeys = Type.entries.associateWith { sampleKeys(ws, it, keyCols, limit) }

        val stagingRows = staging.fetchRows(target.staging, columns, keys, sampleKeys.getValue(Type.DIFFERENT) + sampleKeys.getValue(Type.ONLY_IN_STAGING), scope)
        val productionRows = production.fetchRows(target.production, columns, keys, sampleKeys.getValue(Type.DIFFERENT) + sampleKeys.getValue(Type.ONLY_IN_PRODUCTION), scope)
        val samples = Type.entries.flatMap { type ->
            sampleKeys.getValue(type).map { tuple ->
                val s = if (type != Type.ONLY_IN_PRODUCTION) stagingRows[tuple] else null
                val p = if (type != Type.ONLY_IN_STAGING) productionRows[tuple] else null
                val keyRow = s ?: p
                Sample(
                    type,
                    keys.associate { it.name to keyRow?.get(it.name) },
                    s, p,
                    if (s != null && p != null) columns.filter { !equal(s[it.name], p[it.name], tolerances[it.name]) }.map { it.name } else emptyList(),
                )
            }
        }
        val file = if (samples.isNotEmpty()) writeSamples(ctx, ws, target.name, samples) else null
        return RowDiffResult(
            status = "RAN",
            onlyInStaging = counts.getValue(Type.ONLY_IN_STAGING),
            onlyInProduction = counts.getValue(Type.ONLY_IN_PRODUCTION),
            differentRows = counts.getValue(Type.DIFFERENT),
            sampleLimitPerType = limit,
            sampled = samples.size,
            mismatchesFile = file,
            duplicateKeys = DuplicateKeysResult(duplicates(ws, "dup_s", keys, keyCols, limit), duplicates(ws, "dup_p", keys, keyCols, limit)),
        )
    }

    private fun duplicates(ws: Workspace, table: String, keys: List<ColumnMeta>, keyCols: List<String>, limit: Int): DuplicateKeysSide {
        val total = ws.connection.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { it.next(); it.getLong(1) } }
        val sample = ws.connection.prepareStatement("SELECT ${keyCols.joinToString(", ")}, occurrences FROM $table ORDER BY occurrences DESC, ${keyCols.joinToString(", ")} LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(DuplicateKey(keys.indices.associate { keys[it].name to KeyValues.display(keys[it].category, rs.getString(it + 1)) }, rs.getLong(keys.size + 1)))
                    }
                }
            }
        }
        return DuplicateKeysSide(total, sample)
    }

    /** Without a key rows cannot be matched, so only how often each distinct row content occurs on each side is compared. */
    private fun diffKeyless(ws: Workspace): RowDiffResult {
        val limit = props.mismatchSampleSize
        ws.execute(
            "CREATE TABLE cdiff AS SELECT coalesce(s.fp, p.fp) AS fp, coalesce(s.n, 0) AS sn, coalesce(p.n, 0) AS pn " +
                "FROM (SELECT fp, count(*) AS n FROM stg GROUP BY fp) s FULL OUTER JOIN (SELECT fp, count(*) AS n FROM prod GROUP BY fp) p ON s.fp = p.fp " +
                "WHERE coalesce(s.n, 0) <> coalesce(p.n, 0)",
        )
        val summary = ws.connection.createStatement().use { st ->
            st.executeQuery("SELECT count(*), coalesce(sum(greatest(sn - pn, 0)), 0), coalesce(sum(greatest(pn - sn, 0)), 0) FROM cdiff").use { rs ->
                rs.next(); Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3))
            }
        }
        val sample = ws.connection.prepareStatement("SELECT fp, sn, pn FROM cdiff ORDER BY abs(sn - pn) DESC, fp LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(CountDifference(rs.getString(1), rs.getLong(2), rs.getLong(3))) } }
        }
        return RowDiffResult(
            status = "RAN",
            sampleLimitPerType = limit,
            sampled = sample.size,
            countDifferences = CountDifferencesResult(summary.first, summary.second, summary.third, sample),
            note = "Keyless Target: there is no key to match rows on, so the report can show that rows differ but cannot identify which ones",
        )
    }

    private fun counts(ws: Workspace): Map<Type, Long> {
        val result = Type.entries.associateWith { 0L }.toMutableMap()
        ws.connection.createStatement().use { s ->
            s.executeQuery("SELECT type, count(*) FROM diff GROUP BY type").use { rs ->
                while (rs.next()) result[Type.valueOf(rs.getString(1))] = rs.getLong(2)
            }
        }
        return result
    }

    private fun sampleKeys(ws: Workspace, type: Type, keyCols: List<String>, limit: Int): List<List<String?>> =
        ws.connection.prepareStatement("SELECT ${keyCols.joinToString(", ")} FROM diff WHERE type = ? ORDER BY ${keyCols.joinToString(", ")} LIMIT ?").use { ps ->
            ps.setString(1, type.name)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(keyCols.indices.map { rs.getString(it + 1) }) } }
        }

    private fun writeSamples(ctx: RunContext, ws: Workspace, targetName: String, samples: List<Sample>): String {
        ws.execute("CREATE TABLE sample (mismatch_type VARCHAR, key VARCHAR, staging VARCHAR, production VARCHAR, differing_columns VARCHAR)")
        ws.connection.prepareStatement("INSERT INTO sample VALUES (?, ?, ?, ?, ?)").use { ps ->
            for (s in samples) {
                ps.setString(1, s.type.name)
                ps.setString(2, mapper.writeValueAsString(s.key))
                ps.setString(3, s.staging?.let(mapper::writeValueAsString))
                ps.setString(4, s.production?.let(mapper::writeValueAsString))
                ps.setString(5, mapper.writeValueAsString(s.differing))
                ps.addBatch()
            }
            ps.executeBatch()
        }
        val file = ws.dir.resolve("mismatches.parquet")
        ws.execute("COPY sample TO ${Workspace.literal(file.toString())} (FORMAT PARQUET)")
        return results.writeMismatches(ctx.pipeline, ctx.testRunId, targetName, file)
    }

    private fun equal(a: Any?, b: Any?, tolerance: BigDecimal?) =
        if (a is BigDecimal && b is BigDecimal) a.subtract(b).abs() <= (tolerance ?: BigDecimal.ZERO) else a == b
}
