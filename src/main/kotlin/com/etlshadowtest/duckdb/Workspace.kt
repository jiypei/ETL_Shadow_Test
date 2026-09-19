package com.etlshadowtest.duckdb

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * A Test Run's own embedded DuckDB. Memory is capped and spills to a per-run temporary directory
 * (DuckDB memory is off-heap, so the container limit must account for it), and everything is deleted on close.
 */
class Workspace(val dir: Path, memoryLimit: String, threads: Int? = null) : AutoCloseable {
    val connection: Connection

    /** Environments whose MinIO access has been set up on this connection. */
    val s3Environments = mutableSetOf<String>()

    init {
        Files.createDirectories(dir)
        connection = DriverManager.getConnection("jdbc:duckdb:${dir.resolve("work.duckdb")}")
        connection.createStatement().use { s ->
            s.execute("SET memory_limit = ${literal(memoryLimit)}")
            s.execute("SET temp_directory = ${literal(dir.resolve("spill").toString())}")
            if (threads != null) s.execute("SET threads = $threads")
        }
    }

    fun execute(sql: String) = connection.createStatement().use { it.execute(sql) }

    override fun close() {
        try {
            connection.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    companion object {
        fun literal(text: String) = "'" + text.replace("'", "''") + "'"
    }
}
