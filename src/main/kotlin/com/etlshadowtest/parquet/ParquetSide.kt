package com.etlshadowtest.parquet

import com.etlshadowtest.api.Location
import com.etlshadowtest.config.MinioProperties
import com.etlshadowtest.duckdb.DuckS3
import com.etlshadowtest.run.RunContext
import com.etlshadowtest.target.AggregateQuery
import com.etlshadowtest.target.AggregateSpec
import com.etlshadowtest.target.AggregateValues
import com.etlshadowtest.target.BoundScope
import com.etlshadowtest.target.ColumnMeta
import com.etlshadowtest.target.KeyValues
import com.etlshadowtest.target.TargetSide
import com.etlshadowtest.target.bindTo
import com.etlshadowtest.target.whereClause
import java.sql.Connection
import java.sql.DriverManager
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
                    buildList { while (rs.next()) add(DuckSql.column(rs.getString("column_name"), rs.getString("column_type"))) }
                }
            }
        }
    } catch (e: SQLException) {
        if (e.message?.contains("No files found") == true) null else throw e
    }

    private fun Any?.forDuckDb(): Any? = if (this is Timestamp) toLocalDateTime() else this

    override fun aggregate(location: Location, spec: AggregateSpec, scope: BoundScope?, ctx: RunContext): AggregateValues {
        val query = AggregateQuery(spec, DuckSql)
        connection(ctx).prepareStatement(query.sql(dataset(location), scope)).use { ps ->
            scope.bindTo(ps, 1) { it.forDuckDb() }
            return ps.executeQuery().use(query::read)
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
        val sql = "CREATE TABLE $table AS SELECT ${selects.joinToString(", ")} FROM ${dataset(location)}${scope.whereClause(DuckSql)}"
        connection(ctx).prepareStatement(sql).use { ps ->
            scope.bindTo(ps, 1) { it.forDuckDb() }
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
                scope.bindTo(ps, i) { it.forDuckDb() }
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
        if (column.category.temporal) DuckSql.canonical(column) else DuckSql.quote(column.name)

    private fun readValue(rs: java.sql.ResultSet, index: Int, column: ColumnMeta): Any? =
        if (column.category.numeric) rs.getBigDecimal(index) else rs.getString(index)

    override fun close() {
        if (metadataStarted) metadataDb.close()
    }

    private companion object {
        const val FETCH_KEYS_PER_QUERY = 100
    }
}
