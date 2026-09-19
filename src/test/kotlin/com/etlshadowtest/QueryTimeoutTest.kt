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

/** Production queries may take at most 2 seconds. */
class QueryTimeoutTest : ShadowTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) =
            TestEnvironment.registerProperties(registry, mapOf("shadow.production.oracle.query-timeout" to "2s"))
    }

    /**
     * A Production view that takes effectively forever to read (a 10-billion-row cross join), standing in for a query
     * that runs far too long. Staging has a small ordinary table of the same shape.
     */
    private fun slowProductionView(): String {
        val name = uniqueName("SLOW")
        production.execute(
            "CREATE VIEW $name AS SELECT a.l AS ID, a.l AS X FROM (SELECT LEVEL l FROM DUAL CONNECT BY LEVEL <= 100000) a, " +
                "(SELECT LEVEL k FROM DUAL CONNECT BY LEVEL <= 100000) b",
        )
        production.grantSelect(name)
        staging.createTable(name, "ID NUMBER, X NUMBER")
        staging.insert(name, listOf("ID", "X"), listOf(1, 1), listOf(2, 2), listOf(3, 3))
        return name
    }

    @Test
    fun `a Production query that exceeds its time limit ends and gives ERROR for the affected Target only`() {
        val slow = slowProductionView()
        val fast = uniqueName("FAST")
        for (db in listOf(staging, production)) { db.createTable(fast, "ID NUMBER"); db.insert(fast, listOf("ID"), listOf(1)) }

        val started = System.nanoTime()
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("slow", slow), oracleTarget("fast", fast))).body!!
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0

        val verdicts = done["targets"].associate { it["name"].asText() to it["verdict"].asText() }
        assertThat(verdicts).containsEntry("slow", "ERROR").containsEntry("fast", "PASS")
        assertThat(done["verdict"].asText()).isEqualTo("ERROR")
        assertThat(done["targets"].first { it["name"].asText() == "slow" }["reason"].asText()).contains("time limit").contains("Production")
        assertThat(seconds).describedAs("the slow query was cut off, not run to completion").isLessThan(30.0)
    }
}
