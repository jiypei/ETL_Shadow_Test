package com.etlshadowtest.run

import com.etlshadowtest.api.ApiException
import com.etlshadowtest.api.TargetConfig
import com.etlshadowtest.api.TriggerRequest
import com.etlshadowtest.oracle.OracleSides
import com.etlshadowtest.results.ResultsStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun now(): String = TIMESTAMP.format(Instant.now())

@Service
class TestRunService(
    private val results: ResultsStore,
    private val executor: RunExecutor,
    private val oracle: OracleSides,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun trigger(request: TriggerRequest): RunRecord {
        val ts = now()
        val record = RunRecord(
            testRunId = UUID.randomUUID().toString(),
            pipeline = request.pipeline,
            status = RunStatus.RUNNING,
            startedAt = ts,
            heartbeatAt = ts,
            scope = request.scope,
            config = request.config,
        )
        results.write(record)
        executor.execute { execute(record) }
        return record
    }

    fun get(pipeline: String, testRunId: String): RunRecord =
        results.read(pipeline, testRunId) ?: throw ApiException(HttpStatus.NOT_FOUND, "Test Run $testRunId not found")

    private fun execute(started: RunRecord) {
        val targets = started.config.targets.map { compare(it) }
        val verdict = pipelineVerdict(targets.map { it.verdict })
        results.write(started.copy(status = RunStatus.COMPLETED, finishedAt = now(), heartbeatAt = now(), verdict = verdict, targets = targets))
    }

    private fun compare(target: TargetConfig): TargetResult = try {
        val staging = oracle.staging.rowCount(target.staging)
        val production = oracle.production.rowCount(target.production)
        val agrees = staging == production
        TargetResult(
            name = target.name,
            verdict = if (agrees) Verdict.PASS else Verdict.FAIL,
            aggregateCheck = AggregateCheckResult(
                listOf(CheckResult("row_count", null, staging.toBigDecimal(), production.toBigDecimal(), agrees)),
            ),
        )
    } catch (e: Exception) {
        log.warn("Target {} could not be compared", target.name, e)
        TargetResult(target.name, Verdict.ERROR, reason = e.message)
    }
}

/** FAIL if any Target is FAIL, otherwise ERROR if any is ERROR, otherwise PASS. */
fun pipelineVerdict(verdicts: List<Verdict>): Verdict = when {
    Verdict.FAIL in verdicts -> Verdict.FAIL
    Verdict.ERROR in verdicts -> Verdict.ERROR
    else -> Verdict.PASS
}
