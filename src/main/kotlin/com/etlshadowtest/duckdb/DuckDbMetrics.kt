package com.etlshadowtest.duckdb

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.duckdb.DuckDBConnection
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports what the service's embedded DuckDB instances hold, per role. DuckDB memory is off-heap, so the JVM metrics
 * never show it: without these gauges the container memory limit (docs/operations.md) could not be checked.
 *
 * Each instance is watched through its own duplicate connection, so a scrape never shares a connection with a query.
 */
@Component
class DuckDbMetrics(registry: MeterRegistry) {
    enum class Role(val tag: String) { TEST_RUN("test-run"), HISTORY("history"), PARQUET_SCHEMA("parquet-schema") }

    internal class Snapshot(val used: Long, val spilled: Long, val limit: Long)

    /** One watched instance. Queried and closed under its own lock, so a scrape never touches a closed connection. */
    inner class Watch internal constructor(val role: Role, private val connection: Connection) : AutoCloseable {
        private var closed = false

        @Synchronized
        internal fun snapshot(): Snapshot? {
            if (closed) return null
            return try {
                connection.createStatement().use { s ->
                    s.executeQuery(
                        "SELECT (SELECT coalesce(sum(memory_usage_bytes), 0) FROM duckdb_memory()), " +
                            "(SELECT coalesce(sum(temporary_storage_bytes), 0) FROM duckdb_memory()), " +
                            "(SELECT value FROM duckdb_settings() WHERE name = 'memory_limit')",
                    ).use { rs ->
                        rs.next()
                        Snapshot(rs.getLong(1), rs.getLong(2), parseSize(rs.getString(3)))
                    }
                }
            } catch (e: Exception) {
                log.debug("Could not read DuckDB memory for {}", role.tag, e)
                null
            }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            watches.remove(this)
            runCatching { connection.close() }
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val watches: MutableSet<Watch> = ConcurrentHashMap.newKeySet()

    init {
        for (role in Role.entries) {
            Gauge.builder("duckdb.instances") { watches.count { it.role == role } }
                .description("Open embedded DuckDB instances").tag("role", role.tag).register(registry)
            Gauge.builder("duckdb.memory.used") { sum(role) { it.used } }
                .description("Memory held by DuckDB's buffer manager (off-heap)").baseUnit("bytes").tag("role", role.tag).register(registry)
            Gauge.builder("duckdb.temporary.storage") { sum(role) { it.spilled } }
                .description("Bytes DuckDB has spilled to its temporary directory").baseUnit("bytes").tag("role", role.tag).register(registry)
            Gauge.builder("duckdb.memory.limit") { sum(role) { it.limit } }
                .description("Sum of the memory_limit of the open instances").baseUnit("bytes").tag("role", role.tag).register(registry)
        }
    }

    private fun sum(role: Role, value: (Snapshot) -> Long): Double =
        watches.filter { it.role == role }.sumOf { w -> w.snapshot()?.let(value) ?: 0L }.toDouble()

    /** Starts reporting the DuckDB instance behind [connection]; close the result before closing the instance. */
    fun watch(role: Role, connection: Connection): Watch =
        Watch(role, (connection as DuckDBConnection).duplicate()).also { watches.add(it) }

    companion object {
        private val units = mapOf(
            "B" to 1L, "BYTES" to 1L, "KB" to 1_000L, "MB" to 1_000_000L, "GB" to 1_000_000_000L, "TB" to 1_000_000_000_000L,
            "KIB" to (1L shl 10), "MIB" to (1L shl 20), "GIB" to (1L shl 30), "TIB" to (1L shl 40),
        )

        /** DuckDB reports its limit as text such as "953.6 MiB". */
        internal fun parseSize(text: String?): Long {
            val m = Regex("""^\s*([0-9.]+)\s*([A-Za-z]*)\s*$""").find(text ?: return 0) ?: return 0
            val factor = units[m.groupValues[2].uppercase().ifEmpty { "B" }] ?: return 0
            return (m.groupValues[1].toDouble() * factor).toLong()
        }
    }
}
