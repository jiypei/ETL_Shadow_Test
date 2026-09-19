package com.etlshadowtest

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

class WalkingSkeletonTest : ShadowTestBase() {
    private fun seed(stagingRows: Int, productionRows: Int): String {
        val table = uniqueName("ORDERS")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, NAME VARCHAR2(50)")
        staging.insert(table, listOf("ID", "NAME"), *(1..stagingRows).map { listOf<Any?>(it, "n$it") }.toTypedArray())
        production.insert(table, listOf("ID", "NAME"), *(1..productionRows).map { listOf<Any?>(it, "n$it") }.toTypedArray())
        return table
    }

    private fun JsonNode.rowCountCheck(): JsonNode =
        this["targets"][0]["aggregateCheck"]["checks"].first { it["check"].asText() == "row_count" }

    @Test
    fun `trigger returns a Test Run ID before the comparison finishes and polling shows in-progress then the Verdict`() {
        val table = seed(3, 3)
        val pipeline = uniqueName("p")
        gate.hold()

        val trigger = api.trigger(shadowRequest(pipeline, oracleTarget("orders", table)))
        assertThat(trigger.status).isEqualTo(202)
        assertThat(trigger.testRunId).isNotBlank()

        val running = api.poll(pipeline, trigger.testRunId)
        assertThat(running.status).isEqualTo(200)
        assertThat(running["status"]!!.asText()).isEqualTo("RUNNING")
        assertThat(running["verdict"]!!.isNull).isTrue()

        gate.release()
        val done = api.awaitCompletion(pipeline, trigger.testRunId)
        assertThat(done["status"]!!.asText()).isEqualTo("COMPLETED")
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `equal row counts give PASS`() {
        val table = seed(3, 3)
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("orders", table)))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
        assertThat(done.body!!["targets"][0]["verdict"].asText()).isEqualTo("PASS")
    }

    @Test
    fun `different row counts give FAIL`() {
        val table = seed(3, 4)
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("orders", table)))
        assertThat(done["verdict"]!!.asText()).isEqualTo("FAIL")
        assertThat(done.body!!.rowCountCheck()["agrees"].asBoolean()).isFalse()
    }

    @Test
    fun `the Test Run record in MinIO holds the Verdict, the counts from both sides and the Comparison Config used`() {
        val table = seed(3, 4)
        val pipeline = uniqueName("p")
        val done = api.run(shadowRequest(pipeline, oracleTarget("orders", table)))

        val record = MinioFixtures.readRunJson(pipeline, done.testRunId)
        assertThat(record["verdict"].asText()).isEqualTo("FAIL")
        val counts = record.rowCountCheck()
        assertThat(counts["staging"].asLong()).isEqualTo(3)
        assertThat(counts["production"].asLong()).isEqualTo(4)
        assertThat(record["config"]["targets"][0]["name"].asText()).isEqualTo("orders")
        assertThat(record["config"]["targets"][0]["staging"]["table"].asText()).isEqualTo(table)
    }

    @Test
    fun `a request that tries to supply connection details or credentials is rejected`() {
        val table = seed(1, 1)
        val pipeline = uniqueName("p")
        val withCredentials = shadowRequest(pipeline, oracleTarget("orders", table)) + mapOf("password" to "hunter2")
        assertThat(api.trigger(withCredentials).status).isEqualTo(400)

        val withUrl = shadowRequest(
            pipeline,
            oracleTarget("orders", table, extra = mapOf("jdbcUrl" to "jdbc:oracle:thin:@//evil:1521/x")),
        )
        assertThat(api.trigger(withUrl).status).isEqualTo(400)

        val withConnectionInLocation = shadowRequest(
            pipeline,
            oracleTarget("orders", table) + mapOf("staging" to mapOf("schema" to "S", "table" to "T", "host" to "evil")),
        )
        assertThat(api.trigger(withConnectionInLocation).status).isEqualTo(400)
    }
}

private fun JsonNode.rowCountCheck(): JsonNode =
    this["targets"][0]["aggregateCheck"]["checks"].first { it["check"].asText() == "row_count" }
