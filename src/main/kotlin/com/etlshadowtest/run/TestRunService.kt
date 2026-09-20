package com.etlshadowtest.run

import com.etlshadowtest.api.ApiException
import com.etlshadowtest.api.TriggerRequest
import com.etlshadowtest.compare.TargetComparator
import com.etlshadowtest.config.ShadowProperties
import com.etlshadowtest.duckdb.Workspace
import com.etlshadowtest.validation.RequestValidator
import com.etlshadowtest.results.HistoryStore
import com.etlshadowtest.results.ResultsStore
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

fun timestamp(instant: Instant): String = TIMESTAMP.format(instant)

fun now(): String = timestamp(Instant.now())

@Service
class TestRunService(
    private val results: ResultsStore,
    private val historyStore: HistoryStore,
    private val executor: RunExecutor,
    private val comparator: TargetComparator,
    private val validator: RequestValidator,
    private val props: ShadowProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val active = ConcurrentHashMap<String, ActiveRun>()
    private val slots = Semaphore(props.maxConcurrentRuns.coerceAtLeast(1))
    private val heartbeats = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "heartbeat").apply { isDaemon = true } }

    @PostConstruct
    fun startHeartbeats() {
        val period = props.heartbeatInterval.toMillis().coerceAtLeast(50)
        heartbeats.scheduleWithFixedDelay(::beat, period, period, TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    fun stopHeartbeats() {
        heartbeats.shutdownNow()
    }

    /** Refreshes the heartbeat of every Test Run this instance is running, including ones waiting to start. */
    private fun beat() {
        for (run in active.values) {
            try {
                run.heartbeat()
            } catch (e: Exception) {
                log.warn("Could not write the heartbeat of Test Run {}", run.record.testRunId, e)
            }
        }
    }

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
        // Duckdb memory and temp disk are sized for the cap, so a trigger beyond it is turned away, not queued (ADR 0004).
        if (!slots.tryAcquire()) {
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "The service is at capacity (${props.maxConcurrentRuns} Test Runs in progress). No Test Run was created; try again later.",
                headers = mapOf("Retry-After" to props.retryAfter.seconds.toString()),
            )
        }
        val run = ActiveRun(record, results)
        try {
            run.start()
            active[record.testRunId] = run
            executor.execute { execute(run) }
        } catch (e: Throwable) {
            active.remove(record.testRunId)
            slots.release()
            throw e
        }
        return record
    }

    private fun newWorkspace(testRunId: String) =
        Workspace(Path.of(props.duckdb.tempDirectory).resolve(testRunId), props.duckdb.memoryLimit, props.duckdb.threads)

    fun get(pipeline: String, testRunId: String): RunRecord =
        results.read(pipeline, testRunId)?.let(::reportAbandoned) ?: throw ApiException(HttpStatus.NOT_FOUND, "Test Run $testRunId not found")

    /** A Test Run whose heartbeat stopped is not running any more, whatever its record says: report it as ERROR (abandoned). */
    fun reportAbandoned(record: RunRecord): RunRecord {
        if (!isAbandoned(record.status, record.heartbeatAt)) return record
        return record.copy(status = RunStatus.ABANDONED, verdict = Verdict.ERROR, reason = abandonedReason(record.heartbeatAt))
    }

    fun isAbandoned(status: RunStatus, heartbeatAt: String) =
        status == RunStatus.RUNNING && Duration.between(Instant.parse(heartbeatAt), Instant.now()) > props.heartbeatStaleAfter

    fun abandonedReason(heartbeatAt: String) =
        "Test Run abandoned: its heartbeat stopped at $heartbeatAt, so the service instance running it is presumed dead. Trigger a new Test Run."

    /** A Pipeline's past Test Runs, most recent first, read from the run records in MinIO with DuckDB (ADR 0002). */
    fun history(pipeline: String, from: String?, to: String?, limit: Int): History = History(
        pipeline,
        historyStore.list(pipeline, from, to, limit).map { entry ->
            if (isAbandoned(entry.status, entry.heartbeatAt)) {
                entry.copy(status = RunStatus.ABANDONED, verdict = Verdict.ERROR, reason = abandonedReason(entry.heartbeatAt))
            } else {
                entry
            }
        },
    )

    private fun execute(run: ActiveRun) {
        val started = run.record
        var final: RunRecord
        try {
            val targets = RunContext(started.testRunId, started.pipeline, ::newWorkspace).use { ctx ->
                started.config.targets.map { comparator.compare(ctx, it, started.scope) }
            }
            val unverified = targets.filter { it.verdict == Verdict.SKIPPED }.map { it.name }
            final = started.copy(verdict = pipelineVerdict(targets.map { it.verdict }), targets = targets, unverifiedTargets = unverified)
        } catch (e: Throwable) {
            log.error("Test Run {} failed", started.testRunId, e)
            final = started.copy(verdict = Verdict.ERROR, reason = "Test Run failed: ${e.message}")
        }
        try {
            run.finish(final.copy(status = RunStatus.COMPLETED, finishedAt = now(), heartbeatAt = now()))
        } catch (e: Exception) {
            log.error("Could not store the result of Test Run {}", started.testRunId, e)
        } finally {
            active.remove(started.testRunId)
            slots.release()
        }
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
