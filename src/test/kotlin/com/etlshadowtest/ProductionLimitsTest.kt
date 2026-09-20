package com.etlshadowtest

import com.etlshadowtest.support.ShadowTestSupport
import com.etlshadowtest.support.TestEnvironment
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Several Test Runs at once, against a Production that is allowed 2 connections and 1 query at a time. */
class ProductionLimitsTest : ShadowTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) = TestEnvironment.registerProperties(
            registry,
            mapOf(
                "shadow.production.oracle.max-connections" to "2",
                "shadow.production.oracle.max-parallel-queries" to "1",
            ),
        )
    }

    private fun bigTable(): String {
        val table = uniqueName("LIM")
        for (db in listOf(staging, production)) {
            db.createTable(table, "ID NUMBER, PAYLOAD VARCHAR2(60)")
            db.execute("INSERT /*+ APPEND */ INTO $table SELECT LEVEL, RPAD('row' || LEVEL, 50, 'x') FROM DUAL CONNECT BY LEVEL <= 600000")
        }
        production.update("UPDATE $table SET PAYLOAD = 'changed' WHERE MOD(ID, 1000) = 0")
        return table
    }

    @Test
    fun `the number of connections and the parallelism of Production queries are limited and respected`() {
        val tables = List(4) { bigTable() }
        // Other test classes' cached services keep idle connections of the same account open, so count only what is added.
        val baseline = production.connect("system", TestEnvironment.SYSTEM_PASSWORD).use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM V\$SESSION WHERE USERNAME = ?").use { ps ->
                ps.setString(1, TestEnvironment.PRODUCTION_READER)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        val maxSessions = AtomicInteger()
        val maxActive = AtomicInteger()
        val running = AtomicBoolean(true)
        val sampler = thread(isDaemon = true) {
            production.connect("system", TestEnvironment.SYSTEM_PASSWORD).use { c ->
                c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(CASE WHEN STATUS = 'ACTIVE' THEN 1 ELSE 0 END), 0) FROM V\$SESSION WHERE USERNAME = ?").use { ps ->
                    ps.setString(1, TestEnvironment.PRODUCTION_READER)
                    while (running.get()) {
                        ps.executeQuery().use { rs -> rs.next(); maxSessions.accumulateAndGet(rs.getInt(1), ::maxOf); maxActive.accumulateAndGet(rs.getInt(2), ::maxOf) }
                        Thread.sleep(10)
                    }
                }
            }
        }

        val pipelines = tables.map { uniqueName("p") }
        val ids = tables.mapIndexed { i, t -> api.trigger(shadowRequest(pipelines[i], oracleTarget("t", t))).also { assertThat(it.status).isEqualTo(202) }.testRunId }
        val verdicts = ids.mapIndexed { i, id -> api.awaitCompletion(pipelines[i], id)["verdict"]!!.asText() }
        running.set(false)
        sampler.join()

        assertThat(verdicts).allMatch { it == "FAIL" }
        assertThat(maxActive.get()).describedAs("Production queries running at the same time").isEqualTo(1)
        assertThat(maxSessions.get() - baseline).describedAs("connections opened to Production by this service").isBetween(1, 2)
    }
}
