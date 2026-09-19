package com.etlshadowtest.run

import com.etlshadowtest.api.ApiException
import com.etlshadowtest.api.TriggerRequest
import com.etlshadowtest.compare.TargetComparator
import com.etlshadowtest.config.ShadowProperties
import com.etlshadowtest.duckdb.Workspace
import com.etlshadowtest.validation.RequestValidator
import com.etlshadowtest.results.ResultsStore
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.nio.file.Path
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
    private val comparator: TargetComparator,
    private val validator: RequestValidator,
    private val props: ShadowProperties,
) {
    fun trigger(request: TriggerRequest): RunRecord {
        validator.validate(request)
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

    private fun newWorkspace(testRunId: String) =
        Workspace(Path.of(props.duckdb.tempDirectory).resolve(testRunId), props.duckdb.memoryLimit)

    fun get(pipeline: String, testRunId: String): RunRecord =
        results.read(pipeline, testRunId) ?: throw ApiException(HttpStatus.NOT_FOUND, "Test Run $testRunId not found")

    private fun execute(started: RunRecord) {
        val targets = RunContext(started.testRunId, started.pipeline, ::newWorkspace).use { ctx ->
            started.config.targets.map { comparator.compare(ctx, it, started.scope) }
        }
        val verdict = pipelineVerdict(targets.map { it.verdict })
        val unverified = targets.filter { it.verdict == Verdict.SKIPPED }.map { it.name }
        results.write(
            started.copy(
                status = RunStatus.COMPLETED, finishedAt = now(), heartbeatAt = now(),
                verdict = verdict, targets = targets, unverifiedTargets = unverified,
            ),
        )
    }
}

/**
 * FAIL if any Target is FAIL, otherwise ERROR if any is ERROR, otherwise PASS. SKIPPED Targets do not count,
 * so a Pipeline in which nothing was compared is SKIPPED rather than PASS.
 */
fun pipelineVerdict(verdicts: List<Verdict>): Verdict = when {
    Verdict.FAIL in verdicts -> Verdict.FAIL
    Verdict.ERROR in verdicts -> Verdict.ERROR
    verdicts.all { it == Verdict.SKIPPED } -> Verdict.SKIPPED
    else -> Verdict.PASS
}
