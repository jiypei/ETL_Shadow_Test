package com.etlshadowtest.webhook

import com.etlshadowtest.config.ShadowProperties
import com.etlshadowtest.results.ResultsStore
import com.etlshadowtest.run.CallbackResult
import com.etlshadowtest.run.RunRecord
import com.etlshadowtest.run.now
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

/** Callbacks go only to hosts an operator has allowed, so a caller cannot aim the service at arbitrary systems. */
@Component
class WebhookPolicy(private val props: ShadowProperties) {
    /** Null when [url] may be called, otherwise what is wrong with it. */
    fun problem(url: String): String? {
        val uri = try { URI(url) } catch (e: Exception) { return "callbackUrl is not a valid URL" }
        return when {
            props.webhook.allowedHosts.isEmpty() -> "callbackUrl is not enabled: no callback hosts are configured on this service"
            uri.scheme?.lowercase() !in setOf("http", "https") -> "callbackUrl must be an http or https URL"
            uri.host == null -> "callbackUrl must name a host"
            uri.userInfo != null -> "callbackUrl must not contain credentials"
            props.webhook.allowedHosts.none { it.equals(uri.host, ignoreCase = true) } -> "callbackUrl host '${uri.host}' is not an allowed callback host"
            else -> null
        }
    }
}

/**
 * Sends the Test Run ID and Pipeline Verdict to the caller's callback address when a Test Run ends, whatever its Verdict.
 * A failing callback is retried a bounded number of times with growing pauses, and then recorded in run.json; it never
 * changes the Test Run's Verdict.
 */
@Component
class WebhookNotifier(private val props: ShadowProperties, private val policy: WebhookPolicy, private val results: ResultsStore, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(props.webhook.timeout).followRedirects(HttpClient.Redirect.NEVER).build()
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "webhook").apply { isDaemon = true } }

    @PreDestroy
    fun close() { executor.shutdownNow() }

    fun notify(record: RunRecord) {
        if (record.callbackUrl == null) return
        executor.execute {
            try {
                results.write(record.copy(callback = deliver(record)))
            } catch (e: Exception) {
                log.error("Could not record the callback outcome of Test Run {}", record.testRunId, e)
            }
        }
    }

    private fun deliver(record: RunRecord): CallbackResult {
        val url = record.callbackUrl!!
        policy.problem(url)?.let { return CallbackResult("FAILED", 0, it, null) }
        val body = mapper.writeValueAsString(
            mapOf("testRunId" to record.testRunId, "pipeline" to record.pipeline, "status" to record.status, "verdict" to record.verdict, "reason" to record.reason),
        )
        var pause = props.webhook.initialBackoff
        var lastError: String? = null
        for (attempt in 1..props.webhook.maxAttempts) {
            try {
                val request = HttpRequest.newBuilder(URI.create(url)).timeout(props.webhook.timeout)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                if (status in 200..299) return CallbackResult("DELIVERED", attempt, null, now())
                lastError = "HTTP $status"
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return CallbackResult("FAILED", attempt, "interrupted", null)
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            }
            if (attempt < props.webhook.maxAttempts) {
                Thread.sleep(pause.toMillis())
                pause = pause.multipliedBy(props.webhook.backoffMultiplier.toLong())
            }
        }
        return CallbackResult("FAILED", props.webhook.maxAttempts, lastError, null)
    }
}
