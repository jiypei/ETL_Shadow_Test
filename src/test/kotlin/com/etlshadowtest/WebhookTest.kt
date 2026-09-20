package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.WebhookReceiver
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

@TestPropertySource(
    properties = [
        "shadow.webhook.allowed-hosts[0]=127.0.0.1",
        "shadow.webhook.max-attempts=3",
        "shadow.webhook.initial-backoff=50ms",
        "shadow.webhook.backoff-multiplier=2",
        "shadow.reaper-interval=500ms",
    ],
)
class WebhookTest : ShadowTestBase() {
    private fun table(stagingRows: Int = 1, productionRows: Int = 1): String {
        val table = uniqueName("WH")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER")
        staging.insert(table, listOf("ID"), *(1..stagingRows).map { listOf<Any?>(it) }.toTypedArray())
        production.insert(table, listOf("ID"), *(1..productionRows).map { listOf<Any?>(it) }.toTypedArray())
        return table
    }

    private fun recordedCallback(pipeline: String, id: String, wanted: String): JsonNode {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            val callback = MinioFixtures.readRunJson(pipeline, id)["callback"]
            if (callback != null && !callback.isNull && callback["status"].asText() == wanted) return callback
            check(System.currentTimeMillis() < deadline) { "callback never became $wanted: $callback" }
            Thread.sleep(50)
        }
    }

    @Test
    fun `a trigger with a callback address causes one callback carrying the Test Run ID and the Pipeline Verdict`() {
        WebhookReceiver().use { hook ->
            val pipeline = uniqueName("p")
            val done = api.run(shadowRequest(pipeline, oracleTarget("t", table()), callbackUrl = hook.url))
            assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")

            val received = hook.await(1)
            Thread.sleep(1000)
            assertThat(hook.bodies).hasSize(1)
            assertThat(received.single()["testRunId"].asText()).isEqualTo(done.testRunId)
            assertThat(received.single()["pipeline"].asText()).isEqualTo(pipeline)
            assertThat(received.single()["verdict"].asText()).isEqualTo("PASS")
            val callback = recordedCallback(pipeline, done.testRunId, "DELIVERED")
            assertThat(callback["attempts"].asInt()).isEqualTo(1)
        }
    }

    @Test
    fun `a trigger without a callback address behaves as before`() {
        val pipeline = uniqueName("p")
        val done = api.run(shadowRequest(pipeline, oracleTarget("t", table())))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
        assertThat(MinioFixtures.readRunJson(pipeline, done.testRunId)["callback"].isNull).isTrue()
    }

    @Test
    fun `a failing callback never changes the Verdict and the failure is recorded, after the documented three attempts`() {
        WebhookReceiver(500, 500, 500, 500).use { hook ->
            val pipeline = uniqueName("p")
            val done = api.run(shadowRequest(pipeline, oracleTarget("t", table(1, 2)), callbackUrl = hook.url))
            val callback = recordedCallback(pipeline, done.testRunId, "FAILED")
            assertThat(callback["attempts"].asInt()).isEqualTo(3)
            assertThat(callback["lastError"].asText()).contains("500")
            assertThat(hook.bodies).hasSize(3)
            val polled = api.poll(pipeline, done.testRunId)
            assertThat(polled["verdict"]!!.asText()).isEqualTo("FAIL")
            assertThat(polled["status"]!!.asText()).isEqualTo("COMPLETED")
        }
    }

    @Test
    fun `a callback that fails and then succeeds is retried and recorded as delivered`() {
        WebhookReceiver(503, 500).use { hook ->
            val pipeline = uniqueName("p")
            val done = api.run(shadowRequest(pipeline, oracleTarget("t", table()), callbackUrl = hook.url))
            val callback = recordedCallback(pipeline, done.testRunId, "DELIVERED")
            assertThat(callback["attempts"].asInt()).isEqualTo(3)
            assertThat(hook.bodies).hasSize(3)
        }
    }

    @Test
    fun `an unreachable callback address is a recorded failure, not a failed Test Run`() {
        val pipeline = uniqueName("p")
        val done = api.run(shadowRequest(pipeline, oracleTarget("t", table()), callbackUrl = "http://127.0.0.1:1/hook"))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
        assertThat(recordedCallback(pipeline, done.testRunId, "FAILED")["attempts"].asInt()).isEqualTo(3)
    }

    @Test
    fun `an ERROR Test Run also triggers the callback`() {
        WebhookReceiver().use { hook ->
            val lonely = uniqueName("LONELY").also { staging.createTable(it, "ID NUMBER") }
            val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("t", lonely), callbackUrl = hook.url))
            assertThat(done["verdict"]!!.asText()).isEqualTo("ERROR")
            assertThat(hook.await(1).single()["verdict"].asText()).isEqualTo("ERROR")
        }
    }

    @Test
    fun `an abandoned Test Run also triggers the callback, once, and is recorded as abandoned`() {
        WebhookReceiver().use { hook ->
            val pipeline = uniqueName("p")
            val id = "55555555-5555-5555-5555-555555555555"
            val record = leftBehindRecord(pipeline, id, secondsAgo = 600).put("callbackUrl", hook.url)
            MinioFixtures.writeRunJson(pipeline, id, record)

            val received = hook.await(1)
            Thread.sleep(1500)
            assertThat(hook.bodies).hasSize(1)
            assertThat(received.single()["testRunId"].asText()).isEqualTo(id)
            assertThat(received.single()["status"].asText()).isEqualTo("ABANDONED")
            assertThat(received.single()["verdict"].asText()).isEqualTo("ERROR")
            val stored = MinioFixtures.readRunJson(pipeline, id)
            assertThat(stored["status"].asText()).isEqualTo("ABANDONED")
            assertThat(recordedCallback(pipeline, id, "DELIVERED")["attempts"].asInt()).isEqualTo(1)
        }
    }

    @Test
    fun `a Test Run that is still alive is never swept as abandoned`() {
        WebhookReceiver().use { hook ->
            val pipeline = uniqueName("p")
            gate.hold()
            val trigger = api.trigger(shadowRequest(pipeline, oracleTarget("t", table()), callbackUrl = hook.url))
            Thread.sleep(8000) // longer than the 6s staleness threshold: only the heartbeat keeps it alive
            assertThat(api.poll(pipeline, trigger.testRunId)["status"]!!.asText()).isEqualTo("RUNNING")
            assertThat(hook.bodies).isEmpty()
            gate.release()
            assertThat(api.awaitCompletion(pipeline, trigger.testRunId)["verdict"]!!.asText()).isEqualTo("PASS")
            assertThat(hook.await(1).single()["verdict"].asText()).isEqualTo("PASS")
        }
    }

    @Test
    fun `a callback address that is not allowed is rejected and starts no Test Run`() {
        val t = table()
        for (url in listOf("http://evil.example.com/hook", "http://localhost:9/hook", "file:///etc/passwd", "ftp://127.0.0.1/x", "not a url", "http://user:secret@127.0.0.1:9/hook", "//127.0.0.1/hook")) {
            val pipeline = uniqueName("p")
            val response = api.trigger(shadowRequest(pipeline, oracleTarget("t", t), callbackUrl = url))
            assertThat(response.status).describedAs(url).isEqualTo(400)
            assertThat(response.raw).contains("callbackUrl")
            assertThat(MinioFixtures.list("shadow-results", "results/$pipeline/")).isEmpty()
        }
    }

    @Test
    fun `callbacks are never redirected to another address`() {
        WebhookReceiver().use { target ->
            val redirecting = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            var hits = 0
            redirecting.createContext("/hook") { ex ->
                hits++
                ex.responseHeaders.add("Location", target.url)
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            redirecting.start()
            try {
                val pipeline = uniqueName("p")
                val done = api.run(shadowRequest(pipeline, oracleTarget("t", table()), callbackUrl = "http://127.0.0.1:${redirecting.address.port}/hook"))
                recordedCallback(pipeline, done.testRunId, "FAILED")
                assertThat(hits).isEqualTo(3)
                assertThat(target.bodies).isEmpty()
            } finally {
                redirecting.stop(0)
            }
        }
    }
}
