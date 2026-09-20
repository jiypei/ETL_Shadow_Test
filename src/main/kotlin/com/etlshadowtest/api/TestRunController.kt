package com.etlshadowtest.api

import com.etlshadowtest.auth.PRINCIPAL_ATTRIBUTE
import com.etlshadowtest.auth.Principal
import com.etlshadowtest.run.History
import com.etlshadowtest.run.RunRecord
import com.etlshadowtest.run.TestRunService
import com.etlshadowtest.run.timestamp
import com.etlshadowtest.validation.SAFE_NAME
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.Instant
import java.time.format.DateTimeParseException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val MAX_HISTORY = 500
private val UUID_FORMAT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

@RestController
@RequestMapping("/api/v1")
class TestRunController(private val service: TestRunService) {
    @PostMapping("/test-runs")
    fun trigger(
        @RequestBody request: TriggerRequest,
        @RequestAttribute(PRINCIPAL_ATTRIBUTE) principal: Principal,
    ): ResponseEntity<RunRecord> {
        requireAccess(principal, request.pipeline)
        request.config.targets.firstOrNull { !principal.canUseTarget(it.name) }?.let {
            throw ApiException(HttpStatus.FORBIDDEN, "This token is not authorized for Target '${it.name}'")
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.trigger(request))
    }

    @GetMapping("/pipelines/{pipeline}/test-runs/{testRunId}")
    fun get(
        @PathVariable pipeline: String,
        @PathVariable testRunId: String,
        @RequestAttribute(PRINCIPAL_ATTRIBUTE) principal: Principal,
    ): RunRecord {
        requireAccess(principal, pipeline)
        if (!UUID_FORMAT.matches(testRunId)) throw ApiException(HttpStatus.BAD_REQUEST, "A Test Run ID is a UUID")
        return service.get(pipeline, testRunId)
    }

    @GetMapping("/pipelines/{pipeline}/test-runs")
    fun history(
        @PathVariable pipeline: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestAttribute(PRINCIPAL_ATTRIBUTE) principal: Principal,
    ): History {
        requireAccess(principal, pipeline)
        if (!SAFE_NAME.matches(pipeline)) throw ApiException(HttpStatus.BAD_REQUEST, "Invalid Pipeline name")
        if (limit !in 1..MAX_HISTORY) throw ApiException(HttpStatus.BAD_REQUEST, "limit must be between 1 and $MAX_HISTORY")
        return service.history(pipeline, instant("from", from), instant("to", to), limit)
    }

    /** Normalizes to the format of the timestamps in the run records, so the two can be compared as text. */
    private fun instant(name: String, value: String?): String? = value?.let {
        try {
            timestamp(Instant.parse(it))
        } catch (e: DateTimeParseException) {
            throw ApiException(HttpStatus.BAD_REQUEST, "'$name' must be an ISO-8601 instant such as 2026-01-31T00:00:00Z")
        }
    }

    private fun requireAccess(principal: Principal, pipeline: String) {
        if (!principal.canAccess(pipeline)) throw ApiException(HttpStatus.FORBIDDEN, "This token is not authorized for Pipeline '$pipeline'")
    }
}
