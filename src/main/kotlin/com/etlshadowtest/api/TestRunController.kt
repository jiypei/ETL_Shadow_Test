package com.etlshadowtest.api

import com.etlshadowtest.run.RunRecord
import com.etlshadowtest.run.TestRunService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class TestRunController(private val service: TestRunService) {
    @PostMapping("/test-runs")
    fun trigger(@RequestBody request: TriggerRequest): ResponseEntity<RunRecord> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(service.trigger(request))

    @GetMapping("/pipelines/{pipeline}/test-runs/{testRunId}")
    fun get(@PathVariable pipeline: String, @PathVariable testRunId: String): RunRecord = service.get(pipeline, testRunId)
}
