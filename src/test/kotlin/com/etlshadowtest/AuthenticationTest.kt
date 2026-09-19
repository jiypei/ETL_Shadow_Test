package com.etlshadowtest

import com.etlshadowtest.support.ApiClient
import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.TestEnvironment
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

private const val TOKEN_A = "token-for-pipeline-a-4f8c1d"
private const val TOKEN_B = "token-for-pipeline-b-9a27e0"

/** Runs with the real, configured tokens: each authorizes one Pipeline and its list of Targets. */
@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationTest {
    @LocalServerPort
    private var port: Int = 0

    private val pipelineA = "pipe-a"
    private val pipelineB = "pipe-b"
    private fun client(token: String?) = ApiClient("http://localhost:$port", token)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) {
            TestEnvironment.registerProperties(registry)
            registry.add("shadow.tokens[0].token") { TOKEN_A }
            registry.add("shadow.tokens[0].pipeline") { "pipe-a" }
            registry.add("shadow.tokens[0].targets[0]") { "orders" }
            registry.add("shadow.tokens[1].token") { TOKEN_B }
            registry.add("shadow.tokens[1].pipeline") { "pipe-b" }
            registry.add("shadow.tokens[1].targets[0]") { "customers" }
            registry.add("shadow.tokens[1].targets[1]") { "orders" }
        }

        private val table: String by lazy {
            uniqueName("AUTH").also { t ->
                for (db in listOf(staging, production)) { db.createTable(t, "ID NUMBER"); db.insert(t, listOf("ID"), listOf(1)) }
            }
        }
    }

    private fun request(pipeline: String, vararg targetNames: String) =
        shadowRequest(pipeline, *targetNames.map { oracleTarget(it, table) }.toTypedArray())

    @Test
    fun `a call without a token is rejected`() {
        val response = client(null).trigger(request(pipelineA, "orders"))
        assertThat(response.status).isEqualTo(401)
        assertThat(client(null).poll(pipelineA, "00000000-0000-0000-0000-000000000000").status).isEqualTo(401)
    }

    @Test
    fun `a call with an unknown token is rejected`() {
        assertThat(client("not-a-real-token").trigger(request(pipelineA, "orders")).status).isEqualTo(401)
        assertThat(client("").trigger(request(pipelineA, "orders")).status).isEqualTo(401)
    }

    @Test
    fun `a token can trigger and read Test Runs for its own Pipeline`() {
        val a = client(TOKEN_A)
        val trigger = a.trigger(request(pipelineA, "orders"))
        assertThat(trigger.status).isEqualTo(202)
        val done = a.awaitCompletion(pipelineA, trigger.testRunId)
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
        assertThat(a.poll(pipelineA, trigger.testRunId).status).isEqualTo(200)
    }

    @Test
    fun `a token with several Targets can use any of them`() {
        val b = client(TOKEN_B)
        assertThat(b.run(request(pipelineB, "customers", "orders"))["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `a token cannot trigger a Test Run for another Pipeline`() {
        val response = client(TOKEN_A).trigger(request(pipelineB, "orders"))
        assertThat(response.status).isEqualTo(403)
        assertThat(MinioFixtures.list(TestEnvironment.RESULTS_BUCKET, "results/$pipelineB/").filter { it.contains("mismatches") }).isEmpty()
    }

    @Test
    fun `a token cannot name a Target outside its list`() {
        val response = client(TOKEN_A).trigger(request(pipelineA, "orders", "customers"))
        assertThat(response.status).isEqualTo(403)
        assertThat(response.raw).contains("customers")
    }

    @Test
    fun `a token cannot read another Pipeline's Test Runs`() {
        val trigger = client(TOKEN_B).trigger(request(pipelineB, "orders"))
        assertThat(trigger.status).isEqualTo(202)
        client(TOKEN_B).awaitCompletion(pipelineB, trigger.testRunId)
        assertThat(client(TOKEN_A).poll(pipelineB, trigger.testRunId).status).isEqualTo(403)
        // Asking for it under its own Pipeline's name does not find it either.
        assertThat(client(TOKEN_A).poll(pipelineA, trigger.testRunId).status).isEqualTo(404)
    }

    @Test
    fun `tokens never appear in stored results or logs`(output: CapturedOutput) {
        val a = client(TOKEN_A)
        val done = a.run(request(pipelineA, "orders"))
        a.trigger(request(pipelineB, "orders"))
        client("wrong-token-should-not-be-logged").trigger(request(pipelineA, "orders"))
        assertThat(MinioFixtures.rawRunJson(pipelineA, done.testRunId)).doesNotContain(TOKEN_A)
        assertThat(done.raw).doesNotContain(TOKEN_A)
        assertThat(output.all).doesNotContain(TOKEN_A).doesNotContain(TOKEN_B).doesNotContain("wrong-token-should-not-be-logged")
    }

    @Test
    fun `health checks need no token`() {
        assertThat(client(null).get("/actuator/health").status).isEqualTo(200)
    }
}
