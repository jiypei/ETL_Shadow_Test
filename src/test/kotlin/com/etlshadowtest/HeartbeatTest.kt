package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** A Test Run record as a dead service instance would have left it: RUNNING, with a heartbeat that stopped [secondsAgo] ago. */
fun leftBehindRecord(pipeline: String, testRunId: String, secondsAgo: Long): ObjectNode {
    val mapper = ObjectMapper()
    val stopped = TIMESTAMP.format(Instant.now().minusSeconds(secondsAgo))
    return mapper.createObjectNode().apply {
        put("testRunId", testRunId)
        put("pipeline", pipeline)
        put("status", "RUNNING")
        put("startedAt", stopped)
        put("heartbeatAt", stopped)
        set<com.fasterxml.jackson.databind.JsonNode>("config", mapper.createObjectNode().apply { set<com.fasterxml.jackson.databind.JsonNode>("targets", mapper.createArrayNode()) })
    }
}

class HeartbeatTest : ShadowTestBase() {
    private fun seedTable(): String {
        val table = uniqueName("HB")
        for (db in listOf(staging, production)) { db.createTable(table, "ID NUMBER"); db.insert(table, listOf("ID"), listOf(1)) }
        return table
    }

    @Test
    fun `a running Test Run's record shows a heartbeat that keeps advancing`() {
        val pipeline = uniqueName("p")
        gate.hold()
        val trigger = api.trigger(shadowRequest(pipeline, oracleTarget("t", seedTable())))
        val first = api.poll(pipeline, trigger.testRunId)["heartbeatAt"]!!.asText()

        val deadline = System.nanoTime() + 10_000_000_000L
        var latest = first
        while (latest == first && System.nanoTime() < deadline) {
            Thread.sleep(100)
            latest = api.poll(pipeline, trigger.testRunId)["heartbeatAt"]!!.asText()
        }
        assertThat(Instant.parse(latest)).isAfter(Instant.parse(first))
        assertThat(MinioFixtures.readRunJson(pipeline, trigger.testRunId)["heartbeatAt"].asText()).isNotEqualTo(first)
    }

    @Test
    fun `a Test Run record left in progress with a stale heartbeat is reported as ERROR abandoned`() {
        val pipeline = uniqueName("p")
        val id = "11111111-1111-1111-1111-111111111111"
        MinioFixtures.writeRunJson(pipeline, id, leftBehindRecord(pipeline, id, secondsAgo = 600))

        val polled = api.poll(pipeline, id)
        assertThat(polled.status).isEqualTo(200)
        assertThat(polled["status"]!!.asText()).isEqualTo("ABANDONED")
        assertThat(polled["verdict"]!!.asText()).isEqualTo("ERROR")
        assertThat(polled["reason"]!!.asText()).contains("abandoned")
    }

    @Test
    fun `a Test Run that is still running and heartbeating is never reported as abandoned`() {
        val pipeline = uniqueName("p")
        gate.hold()
        val trigger = api.trigger(shadowRequest(pipeline, oracleTarget("t", seedTable())))
        // Held for longer than the staleness threshold (6s in tests): only the heartbeat keeps it alive.
        repeat(16) {
            Thread.sleep(500)
            val polled = api.poll(pipeline, trigger.testRunId)
            assertThat(polled["status"]!!.asText()).isEqualTo("RUNNING")
        }
        gate.release()
        assertThat(api.awaitCompletion(pipeline, trigger.testRunId)["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `triggering the same Pipeline again after an abandoned Test Run creates a new Test Run and does not resume the old one`() {
        val pipeline = uniqueName("p")
        val oldId = "22222222-2222-2222-2222-222222222222"
        MinioFixtures.writeRunJson(pipeline, oldId, leftBehindRecord(pipeline, oldId, secondsAgo = 600))
        assertThat(api.poll(pipeline, oldId)["status"]!!.asText()).isEqualTo("ABANDONED")

        val trigger = api.trigger(shadowRequest(pipeline, oracleTarget("t", seedTable())))
        assertThat(trigger.status).isEqualTo(202)
        assertThat(trigger.testRunId).isNotEqualTo(oldId)
        assertThat(api.awaitCompletion(pipeline, trigger.testRunId)["verdict"]!!.asText()).isEqualTo("PASS")
        assertThat(api.poll(pipeline, oldId)["status"]!!.asText()).isEqualTo("ABANDONED")
    }

    @Test
    fun `a finished Test Run is not touched by later heartbeats`() {
        val pipeline = uniqueName("p")
        val done = api.run(shadowRequest(pipeline, oracleTarget("t", seedTable())))
        val finishedHeartbeat = MinioFixtures.readRunJson(pipeline, done.testRunId)["heartbeatAt"].asText()
        Thread.sleep(1000)
        val record = MinioFixtures.readRunJson(pipeline, done.testRunId)
        assertThat(record["heartbeatAt"].asText()).isEqualTo(finishedHeartbeat)
        assertThat(record["status"].asText()).isEqualTo("COMPLETED")
    }
}
