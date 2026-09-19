package com.etlshadowtest

import com.etlshadowtest.support.ApiClient
import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.RunGate
import com.etlshadowtest.support.RunGateConfig
import com.etlshadowtest.support.TestEnvironment
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** At most two Test Runs may be in progress at once; a further trigger is rejected with 429 rather than queued (ADR 0004). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["shadow.max-concurrent-runs=2", "shadow.retry-after=45s"],
)
@Import(RunGateConfig::class)
class ConcurrencyCapTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var gate: RunGate

    private val api by lazy { ApiClient("http://localhost:$port", "t") }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) = TestEnvironment.registerProperties(registry)
    }

    @AfterEach
    fun release() = gate.release()

    private fun table(): String {
        val table = uniqueName("CAP")
        for (db in listOf(staging, production)) { db.createTable(table, "ID NUMBER"); db.insert(table, listOf("ID"), listOf(1)) }
        return table
    }

    private fun request(pipeline: String) = shadowRequest(pipeline, oracleTarget("t", table()))

    @Test
    fun `a trigger arriving at the cap is rejected with 429 and a retry hint, so the caller can tell it was not accepted`() {
        gate.hold()
        val first = api.trigger(request(uniqueName("a")))
        val second = api.trigger(request(uniqueName("b")))
        assertThat(first.status).isEqualTo(202)
        assertThat(second.status).isEqualTo(202)

        val rejectedPipeline = uniqueName("c")
        val third = api.trigger(request(rejectedPipeline))
        assertThat(third.status).isEqualTo(429)
        assertThat(third.headers["retry-after"]).isEqualTo("45")
        assertThat(third.raw).contains("capacity").contains("2")
        assertThat(third["testRunId"]).isNull()
        assertThat(MinioFixtures.list(TestEnvironment.RESULTS_BUCKET, "results/$rejectedPipeline/")).isEmpty()
    }

    @Test
    fun `a slot is free again once a Test Run has finished`() {
        gate.hold()
        val a = uniqueName("a")
        val first = api.trigger(request(a))
        api.trigger(request(uniqueName("b")))
        assertThat(api.trigger(request(uniqueName("c"))).status).isEqualTo(429)

        gate.release()
        api.awaitCompletion(a, first.testRunId)
        awaitAccepted(request(uniqueName("d")))
    }

    @Test
    fun `slots are also released by Test Runs that end in ERROR`() {
        val lonely = uniqueName("LONELY")
        staging.createTable(lonely, "ID NUMBER")
        repeat(5) {
            val pipeline = uniqueName("e")
            val trigger = api.trigger(shadowRequest(pipeline, oracleTarget("t", lonely)))
            assertThat(trigger.status).isEqualTo(202)
            assertThat(api.awaitCompletion(pipeline, trigger.testRunId)["verdict"]!!.asText()).isEqualTo("ERROR")
        }
    }

    /** The slot is given back just after the result is stored, so allow a moment for that. */
    private fun awaitAccepted(request: Map<String, Any?>) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            val response = api.trigger(request)
            if (response.status == 202) return
            check(response.status == 429 && System.nanoTime() < deadline) { "unexpected ${response.status}: ${response.raw}" }
            Thread.sleep(100)
        }
    }
}
