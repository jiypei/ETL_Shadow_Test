package com.etlshadowtest.parquet

import com.etlshadowtest.api.Location
import com.etlshadowtest.config.MinioProperties
import com.etlshadowtest.duckdb.DuckS3
import com.etlshadowtest.run.RunContext
import com.etlshadowtest.target.AggregateSpec
import com.etlshadowtest.target.AggregateValues
import com.etlshadowtest.target.BoundScope
import com.etlshadowtest.target.ColumnCategory
import com.etlshadowtest.target.ColumnMeta
import com.etlshadowtest.target.KeyValues
import com.etlshadowtest.target.TargetSide
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp

/** Parquet Targets in one Environment's MinIO bucket. DuckDB reads the data in place; nothing is copied into the service first. */
class ParquetSide(override val environment: String, private val minio: MinioProperties, private val extensionDirectory: String?) : TargetSide, AutoCloseable {
    /** A small in-memory DuckDB, used only to read Parquet footers for metadata. Data queries run in the Test Run's workspace. */
    @Volatile
    private var metadataStarted = false

    private val metadataDb: Connection by lazy {
        metadataStarted = true
        DriverManager.getConnection("jdbc:duckdb:").also { c ->
            c.createStatement().use { it.execute("SET memory_limit = '256MB'") }
            configure(c)
        }
    }

    @Synchronized
    private fun metadataConnection(): Connection = (metadataDb as org.duckdb.DuckDBConnection).duplicate()

    /** Makes s3:// paths for this Environment's bucket readable on [connection]. */
    fun configure(connection: Connection) = DuckS3.configure(connection, "s3_$environment", minio, extensionDirectory)

    private fun connection(ctx: RunContext): Connection {
        val ws = ctx.workspace()
        if (ws.s3Environments.add(environment)) configure(ws.connection)
        return ws.connection
    }

    private fun dataset(location: Location) = DuckSql.dataset(minio.bucket, requireNotNull(location.path) { "A Parquet Target needs a path" })

    override fun columns(location: Location): List<ColumnMeta>? = try {
        metadataConnection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("DESCRIBE SELECT * FROM ${dataset(location)}").use { rs ->
                    buildList { while (rs.next()) add(ColumnMeta(rs.getString("column_name"), rs.getString("column_type"), DuckSql.categoryOf(rs.getString("column_type")), rs.getString("column_type") in setOf("FLOAT", "DOUBLE"))) }
                }
            }
        }
    } catch (e: SQLException) {
        if (e.message?.contains("No files found") == true) null else throw e
    }

    private fun scopeClause(scope: BoundScope?) =
        if (scope == null) "" else " WHERE ${DuckSql.quote(scope.column)} >= ? AND ${DuckSql.quote(scope.column)} < ?"

    private fun bind(ps: PreparedStatement, first: Int, scope: BoundScope?): Int {
        var i = first
        if (scope != null) {
            ps.setObject(i++, scope.from.forDuckDb())
            ps.setObject(i++, scope.to.forDuckDb())
        }
        return i
    }

    private fun Any?.forDuckDb(): Any? = if (this is Timestamp) toLocalDateTime() else this

    override fun aggregate(location: Location, spec: AggregateSpec, scope: BoundScope?, ctx: RunContext): AggregateValues {
        val selects = buildList {
            add("count(*)")
            spec.sumColumns.forEach { add(if (it.dataType == "DOUBLE" || it.dataType == "FLOAT") "fsum(${DuckSql.quote(it.name)})" else "sum(${DuckSql.quote(it.name)})") }
            spec.nullColumns.forEach { add("count(${DuckSql.quote(it.name)})") }
            spec.toleranceColumns.forEach { add("min(${DuckSql.quote(it.name)})"); add("max(${DuckSql.quote(it.name)})") }
            if (spec.fingerprintColumns.isNotEmpty()) add(DuckSql.checksum(spec.fingerprintColumns))
            if (spec.keyColumns.isNotEmpty()) add("count(DISTINCT ${DuckSql.rowFingerprint(spec.keyColumns)})")
        }
        val sql = "SELECT ${selects.joinToString(", ")} FROM ${dataset(location)}${scopeClause(scope)}"
        connection(ctx).prepareStatement(sql).use { ps ->
            bind(ps, 1, scope)
            ps.executeQuery().use { rs ->
                rs.next()
                var i = 1
                val rowCount = rs.getLong(i++)
                val sums = spec.sumColumns.associate { it.name to rs.getBigDecimal(i++) }
                val counts = spec.nullColumns.associate { it.name to rs.getLong(i++) }
                val mins = LinkedHashMap<String, BigDecimal?>()
                val maxs = LinkedHashMap<String, BigDecimal?>()
                for (column in spec.toleranceColumns) { mins[column.name] = rs.getBigDecimal(i++); maxs[column.name] = rs.getBigDecimal(i++) }
                val checksum = if (spec.fingerprintColumns.isNotEmpty()) rs.getBigDecimal(i++) else null
                val duplicates = if (spec.keyColumns.isNotEmpty()) rowCount - rs.getLong(i) else null
                return AggregateValues(rowCount, sums, counts, mins, maxs, checksum, duplicates)
            }
        }
    }

    override fun loadKeyFingerprints(
        location: Location,
        keys: List<ColumnMeta>,
        fingerprint: List<ColumnMeta>,
        scope: BoundScope?,
        ctx: RunContext,
        table: String,
    ) {
        val selects = keys.mapIndexed { i, k -> "${DuckSql.canonical(k)} AS k$i" } + "${DuckSql.rowFingerprint(fingerprint)} AS fp"
        val sql = "CREATE TABLE $table AS SELECT ${selects.joinToString(", ")} FROM ${dataset(location)}${scopeClause(scope)}"
        connection(ctx).prepareStatement(sql).use { ps ->
            bind(ps, 1, scope)
            ps.execute()
        }
    }

    override fun fetchRows(
        location: Location,
        columns: List<ColumnMeta>,
        keys: List<ColumnMeta>,
        keyTuples: List<List<String?>>,
        scope: BoundScope?,
        ctx: RunContext,
    ): Map<List<String?>, Map<String, Any?>> {
        val result = LinkedHashMap<List<String?>, Map<String, Any?>>()
        for (batch in keyTuples.chunked(FETCH_KEYS_PER_QUERY)) {
            val keyMatch = batch.joinToString(" OR ") { "(" + keys.joinToString(" AND ") { k -> "${DuckSql.quote(k.name)} = ?" } + ")" }
            val selects = keys.map { DuckSql.canonical(it) } + columns.map { valueExpression(it) }
            val sql = "SELECT ${selects.joinToString(", ")} FROM ${dataset(location)} WHERE ($keyMatch)" +
                if (scope == null) "" else " AND ${DuckSql.quote(scope.column)} >= ? AND ${DuckSql.quote(scope.column)} < ?"
            connection(ctx).prepareStatement(sql).use { ps ->
                var i = 1
                for (tuple in batch) tuple.forEachIndexed { k, text -> ps.setObject(i++, KeyValues.parse(keys[k].category, text).forDuckDb()) }
                bind(ps, i, scope)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val key = keys.indices.map { rs.getString(it + 1) }
                        result[key] = columns.mapIndexed { n, col -> col.name to readValue(rs, keys.size + n + 1, col) }.toMap(LinkedHashMap())
                    }
                }
            }
        }
        return result
    }

    /** Temporal values are reported in their canonical text form; everything else natively. */
    private fun valueExpression(column: ColumnMeta) =
        if (column.category == ColumnCategory.NUMERIC || column.category == ColumnCategory.TEXT) DuckSql.quote(column.name) else DuckSql.canonical(column)

    private fun readValue(rs: java.sql.ResultSet, index: Int, column: ColumnMeta): Any? =
        if (column.category == ColumnCategory.NUMERIC) rs.getBigDecimal(index) else rs.getString(index)

    override fun close() {
        if (metadataStarted) metadataDb.close()
    }

    private companion object {
        const val FETCH_KEYS_PER_QUERY = 100
    }
}
