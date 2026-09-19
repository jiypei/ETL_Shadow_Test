package com.etlshadowtest.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ApiResponse(val status: Int, val body: JsonNode?, val raw: String) {
    operator fun get(field: String): JsonNode? = body?.get(field)
    val testRunId: String get() = body!!["testRunId"].asText()
}

/** A caller of the public API, exactly as a CI job would be. */
class ApiClient(private val baseUrl: String, private val token: String? = null) {
    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()

    fun withToken(token: String?) = ApiClient(baseUrl, token)

    fun trigger(request: Map<String, Any?>): ApiResponse = post("/api/v1/test-runs", request)

    fun post(path: String, body: Any): ApiResponse = send(builder(path).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).header("Content-Type", "application/json"))

    fun get(path: String): ApiResponse = send(builder(path).GET())

    fun poll(pipeline: String, testRunId: String): ApiResponse = get("/api/v1/pipelines/$pipeline/test-runs/$testRunId")

    fun history(pipeline: String, query: String = ""): ApiResponse = get("/api/v1/pipelines/$pipeline/test-runs$query")

    /** Polls until the Test Run is no longer running. */
    fun awaitCompletion(pipeline: String, testRunId: String, timeout: Duration = Duration.ofSeconds(120)): ApiResponse {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val r = poll(pipeline, testRunId)
            check(r.status == 200) { "poll returned ${r.status}: ${r.raw}" }
            if (r["status"]?.asText() != "RUNNING") return r
            check(System.nanoTime() < deadline) { "Test Run $testRunId still RUNNING after $timeout: ${r.raw}" }
            Thread.sleep(200)
        }
    }

    /** Triggers and waits for the finished Test Run. */
    fun run(request: Map<String, Any?>): ApiResponse {
        val t = trigger(request)
        check(t.status == 202) { "trigger returned ${t.status}: ${t.raw}" }
        return awaitCompletion(request["pipeline"] as String, t.testRunId)
    }

    private fun builder(path: String): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60))
        if (token != null) b.header("Authorization", "Bearer $token")
        return b
    }

    private fun send(b: HttpRequest.Builder): ApiResponse {
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        val node = runCatching { mapper.readTree(r.body()) }.getOrNull()
        return ApiResponse(r.statusCode(), node, r.body())
    }
}
