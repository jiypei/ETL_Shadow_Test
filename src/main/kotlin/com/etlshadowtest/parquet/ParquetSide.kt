package com.etlshadowtest.parquet

import com.etlshadowtest.api.Location
import com.etlshadowtest.config.MinioProperties
import com.etlshadowtest.duckdb.DuckS3
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
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

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

    override fun readerFor(ctx: RunContext): TargetReader = object : TargetReader {
        /** The Test Run's own DuckDB, created on first use so a Test Run that never needs it never makes it. */
        private val connection by lazy { connection(ctx) }

        override fun aggregate(location: Location, spec: AggregateSpec, scope: BoundScope?): AggregateValues {
            val query = AggregateQuery(spec, DuckSql)
            connection.prepareStatement(query.sql(dataset(location), scope)).use { ps ->
                scope.bindTo(ps, 1, DuckSql::bindable)
                return ps.executeQuery().use(query::read)
            }
        }

        /** DuckDB reads the Parquet files in place from MinIO and writes the table itself. */
        override fun loadKeyFingerprints(location: Location, into: KeyFingerprintTable, keys: List<ColumnMeta>, fingerprint: List<ColumnMeta>, scope: BoundScope?) {
            val sql = "CREATE TABLE ${into.name} AS ${into.select(DuckSql, keys, fingerprint, dataset(location), scope)}"
            connection.prepareStatement(sql).use { ps ->
                scope.bindTo(ps, 1, DuckSql::bindable)
                ps.execute()
            }
        }

        override fun fetchRows(location: Location, columns: List<ColumnMeta>, keys: List<ColumnMeta>, keyTuples: List<List<String?>>, scope: BoundScope?) =
            RowFetch(DuckSql).fetch(dataset(location), columns, keys, keyTuples, scope) { sql, bind, readResult ->
                connection.prepareStatement(sql).use { ps -> bind(ps); ps.executeQuery().use(readResult) }
            }
    }

    override fun close() {
        if (metadataStarted) metadataDb.close()
    }
}
