package com.etlshadowtest.api

import com.etlshadowtest.auth.PRINCIPAL_ATTRIBUTE
import com.etlshadowtest.auth.Principal
import com.etlshadowtest.run.RunRecord
import com.etlshadowtest.run.TestRunService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
        return service.get(pipeline, testRunId)
    }

    private fun requireAccess(principal: Principal, pipeline: String) {
        if (!principal.canAccess(pipeline)) throw ApiException(HttpStatus.FORBIDDEN, "This token is not authorized for Pipeline '$pipeline'")
    }
}
