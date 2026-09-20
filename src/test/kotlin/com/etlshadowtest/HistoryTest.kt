package com.etlshadowtest

import com.etlshadowtest.support.ApiResponse
import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class HistoryTest : ShadowTestBase() {
    private fun table(stagingRows: Int, productionRows: Int): String {
        val table = uniqueName("H")
        staging.createTable(table, "ID NUMBER, DAY DATE")
        production.createTable(table, "ID NUMBER, DAY DATE")
        staging.insert(table, listOf("ID", "DAY"), *(1..stagingRows).map { listOf<Any?>(it, LocalDate.of(2026, 1, 10)) }.toTypedArray())
        production.insert(table, listOf("ID", "DAY"), *(1..productionRows).map { listOf<Any?>(it, LocalDate.of(2026, 1, 10)) }.toTypedArray())
        return table
    }

    private val january = mapOf("from" to "2026-01-01", "to" to "2026-02-01")

    private fun passing(name: String = "t") = oracleTarget(name, table(2, 2), extra = mapOf("scopeColumn" to "DAY"))
    private fun failing(name: String = "t") = oracleTarget(name, table(2, 3), extra = mapOf("scopeColumn" to "DAY"))

    private fun run(pipeline: String, vararg targets: Map<String, Any?>): ApiResponse = api.run(shadowRequest(pipeline, *targets, scope = january))

    private fun ApiResponse.runs(): List<JsonNode> = body!!["testRuns"].toList()

    @Test
    fun `the history lists a Pipeline's Test Runs with scope, Pipeline Verdict, per-Target Verdicts and time`() {
        val pipeline = uniqueName("p")
        val done = run(pipeline, passing("orders"), failing("customers"))

        val history = api.history(pipeline)
        assertThat(history.status).isEqualTo(200)
        assertThat(history["pipeline"]!!.asText()).isEqualTo(pipeline)
        val entry = history.runs().single()
        assertThat(entry["testRunId"].asText()).isEqualTo(done.testRunId)
        assertThat(entry["verdict"].asText()).isEqualTo("FAIL")
        assertThat(entry["status"].asText()).isEqualTo("COMPLETED")
        assertThat(entry["scope"]["from"].asText()).isEqualTo("2026-01-01")
        assertThat(entry["scope"]["to"].asText()).isEqualTo("2026-02-01")
        assertThat(entry["targets"].associate { it["name"].asText() to it["verdict"].asText() })
            .containsExactlyInAnyOrderEntriesOf(mapOf("orders" to "PASS", "customers" to "FAIL"))
        assertThat(entry["startedAt"].asText()).isEqualTo(done["startedAt"]!!.asText())
        assertThat(entry["finishedAt"].asText()).isEqualTo(done["finishedAt"]!!.asText())
    }

    @Test
    fun `runs are ordered with the most recent first`() {
        val pipeline = uniqueName("p")
        val ids = (1..3).map { run(pipeline, passing()).testRunId.also { Thread.sleep(20) } }
        assertThat(api.history(pipeline).runs().map { it["testRunId"].asText() }).containsExactlyElementsOf(ids.reversed())
    }

    @Test
    fun `the history can be limited to a time range`() {
        val pipeline = uniqueName("p")
        val runs = (1..3).map { run(pipeline, passing()).also { Thread.sleep(50) } }
        val started = runs.map { it["startedAt"]!!.asText() }

        val middleOnly = api.history(pipeline, "?from=${started[1]}&to=${started[2]}")
        assertThat(middleOnly.runs().map { it["testRunId"].asText() }).containsExactly(runs[1].testRunId)

        val fromSecond = api.history(pipeline, "?from=${started[1]}")
        assertThat(fromSecond.runs().map { it["testRunId"].asText() }).containsExactly(runs[2].testRunId, runs[1].testRunId)

        val beforeThird = api.history(pipeline, "?to=${started[2]}")
        assertThat(beforeThird.runs().map { it["testRunId"].asText() }).containsExactly(runs[1].testRunId, runs[0].testRunId)

        assertThat(api.history(pipeline, "?from=2999-01-01T00:00:00Z").runs()).isEmpty()
    }

    @Test
    fun `the number of runs returned can be limited`() {
        val pipeline = uniqueName("p")
        val ids = (1..3).map { run(pipeline, passing()).testRunId }
        assertThat(api.history(pipeline, "?limit=2").runs().map { it["testRunId"].asText() }).containsExactly(ids[2], ids[1])
    }

    @Test
    fun `abandoned and ERROR Test Runs appear in the history`() {
        val pipeline = uniqueName("p")
        val lonely = uniqueName("LONELY").also { staging.createTable(it, "ID NUMBER") }
        val error = api.run(shadowRequest(pipeline, oracleTarget("t", lonely)))
        assertThat(error["verdict"]!!.asText()).isEqualTo("ERROR")
        val abandonedId = "44444444-4444-4444-4444-444444444444"
        MinioFixtures.writeRunJson(pipeline, abandonedId, leftBehindRecord(pipeline, abandonedId, secondsAgo = 600))

        val byId = api.history(pipeline).runs().associateBy { it["testRunId"].asText() }
        assertThat(byId.getValue(error.testRunId)["verdict"].asText()).isEqualTo("ERROR")
        assertThat(byId.getValue(abandonedId)["status"].asText()).isEqualTo("ABANDONED")
        assertThat(byId.getValue(abandonedId)["verdict"].asText()).isEqualTo("ERROR")
        assertThat(byId.getValue(abandonedId)["reason"].asText()).contains("abandoned")
    }

    @Test
    fun `a Test Run still in progress appears as RUNNING`() {
        val pipeline = uniqueName("p")
        gate.hold()
        val trigger = api.trigger(shadowRequest(pipeline, passing(), scope = january))
        val entry = api.history(pipeline).runs().single()
        assertThat(entry["testRunId"].asText()).isEqualTo(trigger.testRunId)
        assertThat(entry["status"].asText()).isEqualTo("RUNNING")
        assertThat(entry["verdict"].isNull).isTrue()
    }

    @Test
    fun `a Pipeline with no Test Runs returns an empty history, not an error`() {
        val response = api.history(uniqueName("never-run"))
        assertThat(response.status).isEqualTo(200)
        assertThat(response.runs()).isEmpty()
    }

    @Test
    fun `the history of one Pipeline never includes another Pipeline's Test Runs`() {
        val a = uniqueName("pa")
        val b = uniqueName("pb")
        val runA = run(a, passing())
        val runB = run(b, failing())
        assertThat(api.history(a).runs().map { it["testRunId"].asText() }).containsExactly(runA.testRunId)
        assertThat(api.history(b).runs().map { it["testRunId"].asText() }).containsExactly(runB.testRunId)
    }

    @Test
    fun `a Pipeline name that is a prefix of another does not pull in the other's runs`() {
        val short = uniqueName("pfx")
        val long = short + "-longer"
        run(long, passing())
        assertThat(api.history(short).runs()).isEmpty()
    }

    @Test
    fun `malformed parameters are rejected`() {
        val pipeline = uniqueName("p")
        assertThat(api.history(pipeline, "?from=yesterday").status).isEqualTo(400)
        assertThat(api.history(pipeline, "?limit=0").status).isEqualTo(400)
        assertThat(api.history(pipeline, "?limit=abc").status).isEqualTo(400)
        assertThat(api.history("../etc").status).isIn(400, 404)
    }
}
