package com.etlshadowtest.support

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Writes small Parquet datasets into the shared MinIO, the way a Pipeline would leave a Parquet Target there. */
object ParquetFixtures {
    private fun connection(): Connection {
        val c = DriverManager.getConnection("jdbc:duckdb:")
        val endpoint = TestEnvironment.minio.s3URL.removePrefix("http://")
        c.createStatement().use { s ->
            s.execute("INSTALL httpfs")
            s.execute("LOAD httpfs")
            s.execute(
                "CREATE SECRET (TYPE S3, KEY_ID '${TestEnvironment.minio.userName}', SECRET '${TestEnvironment.minio.password}', " +
                    "ENDPOINT '$endpoint', URL_STYLE 'path', USE_SSL false)",
            )
        }
        return c
    }

    /**
     * Writes [rows] with the given column DDL (for example "ID BIGINT, NAME VARCHAR") as one Parquet file
     * at s3://[bucket]/[path]/[file].
     */
    fun write(bucket: String, path: String, columns: String, rows: List<List<Any?>>, file: String = "part-0.parquet") {
        connection().use { c ->
            val table = "t_" + UUID.randomUUID().toString().replace("-", "")
            c.createStatement().use { it.execute("CREATE TABLE $table ($columns)") }
            if (rows.isNotEmpty()) {
                val placeholders = rows.first().joinToString(",") { "?" }
                c.prepareStatement("INSERT INTO $table VALUES ($placeholders)").use { ps ->
                    for (row in rows) {
                        row.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
            c.createStatement().use { it.execute("COPY $table TO 's3://$bucket/$path/$file' (FORMAT PARQUET)") }
        }
    }

    fun staging(path: String, columns: String, rows: List<List<Any?>>, file: String = "part-0.parquet") =
        write(TestEnvironment.STAGING_BUCKET, path, columns, rows, file)

    fun production(path: String, columns: String, rows: List<List<Any?>>, file: String = "part-0.parquet") =
        write(TestEnvironment.PRODUCTION_BUCKET, path, columns, rows, file)
}

fun parquetTarget(
    name: String,
    stagingPath: String,
    productionPath: String = stagingPath,
    extra: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = mapOf(
    "name" to name,
    "type" to "PARQUET",
    "staging" to mapOf("path" to stagingPath),
    "production" to mapOf("path" to productionPath),
) + (if ("scopeColumn" in extra) emptyMap() else mapOf("fullRefresh" to true)) +
    (if ("keyless" in extra) emptyMap() else mapOf("keyColumns" to listOf("ID"))) + extra
