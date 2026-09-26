package com.etlshadowtest

import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** JVM and DuckDB CPU and memory, as Prometheus scrapes them for the Grafana dashboard (docs/monitoring.md). */
// Spring Boot tests switch metrics export off unless asked.
@AutoConfigureObservability(tracing = false)
class MonitoringTest : ShadowTestBase() {
    private val sample = Regex("""^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+(\S+)""")

    /** Every sample on the scrape page, as (metric name, labels, value). */
    private fun scrape(): List<Triple<String, Map<String, String>, Double>> {
        val r = api.withToken(null).get("/actuator/prometheus")
        check(r.status == 200) { "scrape returned ${r.status}: ${r.raw.take(500)}" }
        return r.raw.lines().filter { !it.startsWith("#") }.mapNotNull { line ->
            sample.find(line)?.let { m ->
                val labels = Regex("""(\w+)="([^"]*)"""").findAll(m.groupValues[3]).associate { it.groupValues[1] to it.groupValues[2] }
                Triple(m.groupValues[1], labels, m.groupValues[4].toDouble())
            }
        }
    }

    private fun List<Triple<String, Map<String, String>, Double>>.value(name: String, vararg labels: Pair<String, String>): Double? =
        filter { (n, l, _) -> n == name && labels.all { (k, v) -> l[k] == v } }.map { it.third }.takeIf { it.isNotEmpty() }?.sum()

    @Test
    fun `the Prometheus endpoint needs no token and reports JVM memory and CPU`() {
        val metrics = scrape()
        assertThat(metrics.value("jvm_memory_used_bytes", "area" to "heap")).isGreaterThan(0.0)
        assertThat(metrics.value("jvm_memory_used_bytes", "area" to "nonheap")).isGreaterThan(0.0)
        assertThat(metrics.value("process_cpu_usage")).isNotNull()
        assertThat(metrics.value("jvm_threads_live_threads")).isGreaterThan(0.0)
        assertThat(metrics.filter { it.first == "jvm_memory_used_bytes" }.map { it.second["application"] }).containsOnly("etl-shadow-test")
    }

    @Test
    fun `the DuckDB of a Test Run reports its memory and limit while it runs, and is gone when it ends`() {
        val table = uniqueName("MON")
        for (db in listOf(staging, production)) {
            db.createTable(table, "ID NUMBER, PAYLOAD VARCHAR2(60)")
            db.execute("INSERT /*+ APPEND */ INTO $table SELECT LEVEL, RPAD('row' || LEVEL, 50, 'x') FROM DUAL CONNECT BY LEVEL <= 300000")
        }
        // A difference makes the Row Diff load both sides into the Test Run's DuckDB.
        production.update("UPDATE $table SET PAYLOAD = 'changed' WHERE MOD(ID, 1000) = 0")

        val before = scrape()
        val runCpuBefore = before.value("shadow_process_cpu_time_seconds_total", "part" to "test-runs")!!

        val peakInstances = AtomicLong()
        val peakUsed = AtomicLong()
        val peakLimit = AtomicLong()
        val watching = AtomicBoolean(true)
        val watcher = thread(isDaemon = true) {
            while (watching.get()) {
                runCatching {
                    val m = scrape()
                    peakInstances.accumulateAndGet(m.value("duckdb_instances", "role" to "test-run")!!.toLong(), ::maxOf)
                    peakUsed.accumulateAndGet(m.value("duckdb_memory_used_bytes", "role" to "test-run")!!.toLong(), ::maxOf)
                    peakLimit.accumulateAndGet(m.value("duckdb_memory_limit_bytes", "role" to "test-run")!!.toLong(), ::maxOf)
                }
                Thread.sleep(20)
            }
        }
        val done = try {
            api.run(shadowRequest(uniqueName("p"), oracleTarget("mon", table)))
        } finally {
            watching.set(false)
            watcher.join()
        }
        assertThat(done["targets"]!![0]["rowDiff"]["status"].asText()).isEqualTo("RAN")

        assertThat(peakInstances.get()).describedAs("Test Run DuckDB instances seen while running").isEqualTo(1)
        assertThat(peakUsed.get()).describedAs("bytes the Test Run's DuckDB held").isGreaterThan(0)
        // The configured 1GB, which DuckDB reports as 953.6 MiB.
        assertThat(peakLimit.get()).isBetween(990_000_000L, 1_000_000_000L)

        val after = scrape()
        assertThat(after.value("duckdb_instances", "role" to "test-run")).isEqualTo(0.0)
        assertThat(after.value("duckdb_memory_used_bytes", "role" to "test-run")).isEqualTo(0.0)
        assertThat(after.value("shadow_process_cpu_time_seconds_total", "part" to "test-runs")).isGreaterThan(runCpuBefore)
        assertThat(after.value("shadow_process_cpu_time_seconds_total", "part" to "native")).isGreaterThan(0.0)
    }

    @Test
    fun `the history DuckDB reports its fixed memory limit once used`() {
        api.history(uniqueName("p")).also { check(it.status == 200) { it.raw } }
        val metrics = scrape()
        assertThat(metrics.value("duckdb_instances", "role" to "history")).isEqualTo(1.0)
        // 256MB, which DuckDB reports as 244.1 MiB.
        assertThat(metrics.value("duckdb_memory_limit_bytes", "role" to "history")).isBetween(250_000_000.0, 260_000_000.0)
        assertThat(metrics.value("duckdb_memory_used_bytes", "role" to "history")).isNotNull()
    }
}
