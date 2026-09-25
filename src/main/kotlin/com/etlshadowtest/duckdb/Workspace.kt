package com.etlshadowtest.duckdb

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * A Test Run's own embedded DuckDB. Memory is capped and spills to a per-run temporary directory
 * (DuckDB memory is off-heap, so the container limit must account for it), and everything is deleted on close.
 */
class Workspace(val dir: Path, memoryLimit: String, threads: Int, metrics: DuckDbMetrics) : AutoCloseable {
    val connection: Connection
    private val watch: DuckDbMetrics.Watch

    /** Environments whose MinIO access has been set up on this connection. */
    val s3Environments = mutableSetOf<String>()

    init {
        Files.createDirectories(dir)
        connection = DriverManager.getConnection("jdbc:duckdb:${dir.resolve("work.duckdb")}")
        connection.createStatement().use { s ->
            s.execute("SET memory_limit = ${literal(memoryLimit)}")
            s.execute("SET temp_directory = ${literal(dir.resolve("spill").toString())}")
            s.execute("SET threads = $threads")
            // Row order is never needed, and keeping it makes large loads hold more in memory.
            s.execute("SET preserve_insertion_order = false")
        }
        watch = metrics.watch(DuckDbMetrics.Role.TEST_RUN, connection)
    }

    fun execute(sql: String) = connection.createStatement().use { it.execute(sql) }

    override fun close() {
        try {
            watch.close()
            connection.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    companion object {
        fun literal(text: String) = "'" + text.replace("'", "''") + "'"
    }
}
