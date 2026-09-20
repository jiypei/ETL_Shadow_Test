package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ComparisonScopeTest : ShadowTestBase() {
    private val january = mapOf("from" to "2026-01-01", "to" to "2026-02-01")

    private fun table(stagingDays: List<String>, productionDays: List<String>): String {
        val table = uniqueName("SCOPED")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, LOAD_DATE DATE")
        fun rows(days: List<String>) = days.mapIndexed { i, d -> listOf<Any?>(i + 1, LocalDate.parse(d)) }.toTypedArray()
        staging.insert(table, listOf("ID", "LOAD_DATE"), *rows(stagingDays))
        production.insert(table, listOf("ID", "LOAD_DATE"), *rows(productionDays))
        return table
    }

    private fun scoped(table: String) = oracleTarget("t", table, extra = mapOf("scopeColumn" to "LOAD_DATE"))

    @Test
    fun `rows outside the requested range are ignored on both sides`() {
        val t = table(listOf("2026-01-10", "2026-03-05", "2026-04-01"), listOf("2026-01-10", "2025-12-31"))
        val done = api.run(shadowRequest(uniqueName("p"), scoped(t), scope = january))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `rows inside the range that differ give FAIL`() {
        val t = table(listOf("2026-01-10", "2026-01-11"), listOf("2026-01-10"))
        val done = api.run(shadowRequest(uniqueName("p"), scoped(t), scope = january))
        assertThat(done["verdict"]!!.asText()).isEqualTo("FAIL")
    }

    @Test
    fun `the range starts at from and ends before to`() {
        val t = table(listOf("2026-01-01", "2026-02-01"), listOf("2026-01-01"))
        val done = api.run(shadowRequest(uniqueName("p"), scoped(t), scope = january))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `a Full-Refresh Target is compared whole whatever range the trigger supplies`() {
        val t = table(listOf("2026-01-10", "2026-03-05"), listOf("2026-01-10"))
        val fullRefresh = oracleTarget("t", t, extra = mapOf("fullRefresh" to true))
        val done = api.run(shadowRequest(uniqueName("p"), fullRefresh, scope = january))
        assertThat(done["verdict"]!!.asText()).isEqualTo("FAIL")
    }

    @Test
    fun `a failing Full-Refresh Target is reported with a note that source drift may be the cause`() {
        val t = table(listOf("2026-01-10", "2026-03-05"), listOf("2026-01-10"))
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("t", t)))
        val notes = done["targets"]!![0]["notes"].map { it.asText() }
        assertThat(notes).anyMatch { it.contains("source drift") }
    }

    @Test
    fun `no drift note is given when a Full-Refresh Target passes or a scoped Target fails`() {
        val same = table(listOf("2026-01-10"), listOf("2026-01-10"))
        val passing = api.run(shadowRequest(uniqueName("p"), oracleTarget("t", same)))
        assertThat(passing["targets"]!![0]["notes"]).isEmpty()

        val differing = table(listOf("2026-01-10", "2026-01-11"), listOf("2026-01-10"))
        val failing = api.run(shadowRequest(uniqueName("p"), scoped(differing), scope = january))
        assertThat(failing["verdict"]!!.asText()).isEqualTo("FAIL")
        assertThat(failing["targets"]!![0]["notes"]).isEmpty()
    }

    @Test
    fun `a Target that is neither Full-Refresh nor given a scope column is rejected with a clear error`() {
        val t = table(listOf("2026-01-10"), listOf("2026-01-10"))
        val target = oracleTarget("orders", t, extra = mapOf("fullRefresh" to false))
        val response = api.trigger(shadowRequest(uniqueName("p"), target, scope = january))
        assertThat(response.status).isEqualTo(400)
        assertThat(response.raw).contains("orders").contains("scope column").contains("Full-Refresh")
    }

    @Test
    fun `a Full-Refresh Target that also names a scope column is rejected`() {
        val t = table(listOf("2026-01-10"), listOf("2026-01-10"))
        val target = oracleTarget("orders", t, extra = mapOf("fullRefresh" to true, "scopeColumn" to "LOAD_DATE"))
        val response = api.trigger(shadowRequest(uniqueName("p"), target, scope = january))
        assertThat(response.status).isEqualTo(400)
        assertThat(response.raw).contains("orders")
    }

    @Test
    fun `a trigger without the range that a scoped Target needs is rejected`() {
        val t = table(listOf("2026-01-10"), listOf("2026-01-10"))
        val response = api.trigger(shadowRequest(uniqueName("p"), scoped(t)))
        assertThat(response.status).isEqualTo(400)
        assertThat(response.raw).contains("scope")
    }

    @Test
    fun `a scope range that cannot be read for the scope column is rejected`() {
        val t = table(listOf("2026-01-10"), listOf("2026-01-10"))
        val response = api.trigger(shadowRequest(uniqueName("p"), scoped(t), scope = mapOf("from" to "not-a-date", "to" to "2026-02-01")))
        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun `the scope used is stored in the Test Run record`() {
        val t = table(listOf("2026-01-10"), listOf("2026-01-10"))
        val pipeline = uniqueName("p")
        val done = api.run(shadowRequest(pipeline, scoped(t), scope = january))
        assertThat(done["scope"]!!["from"].asText()).isEqualTo("2026-01-01")
        val record = MinioFixtures.readRunJson(pipeline, done.testRunId)
        assertThat(record["scope"]["from"].asText()).isEqualTo("2026-01-01")
        assertThat(record["scope"]["to"].asText()).isEqualTo("2026-02-01")
    }

    @Test
    fun `an empty or inverted scope range is rejected instead of comparing nothing`() {
        val t = table(listOf("2026-01-10"), listOf("2026-01-10"))
        for (range in listOf(mapOf("from" to "2026-02-01", "to" to "2026-01-01"), mapOf("from" to "2026-01-01", "to" to "2026-01-01"))) {
            val response = api.trigger(shadowRequest(uniqueName("p"), scoped(t), scope = range))
            assertThat(response.status).isEqualTo(400)
            assertThat(response.raw).contains("must be before")
        }
    }
}
