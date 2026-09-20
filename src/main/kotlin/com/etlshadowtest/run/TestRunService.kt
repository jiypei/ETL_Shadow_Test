package com.etlshadowtest.run

import com.etlshadowtest.api.ApiException
import com.etlshadowtest.api.TriggerRequest
import com.etlshadowtest.compare.TargetComparator
import com.etlshadowtest.config.ShadowProperties
import com.etlshadowtest.duckdb.Workspace
import com.etlshadowtest.validation.RequestValidator
import com.etlshadowtest.results.HistoryStore
import com.etlshadowtest.webhook.WebhookNotifier
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
    private val webhook: WebhookNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val active = ConcurrentHashMap<String, ActiveRun>()
    private val slots = Semaphore(props.maxConcurrentRuns.coerceAtLeast(1))
    private val heartbeats = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "heartbeat").apply { isDaemon = true } }

    /** Kept apart from the heartbeats: a slow scan of MinIO must never delay a heartbeat and make a live Test Run look dead. */
    private val sweeper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "abandoned-sweep").apply { isDaemon = true } }

    @PostConstruct
    fun startHeartbeats() {
        require(props.heartbeatStaleAfter >= props.heartbeatInterval.multipliedBy(3)) {
            "shadow.heartbeat-stale-after must be at least three times shadow.heartbeat-interval, or live Test Runs would look abandoned"
        }
        val period = props.heartbeatInterval.toMillis().coerceAtLeast(50)
        heartbeats.scheduleWithFixedDelay(::beat, period, period, TimeUnit.MILLISECONDS)
        val sweep = props.reaperInterval.toMillis().coerceAtLeast(100)
        sweeper.scheduleWithFixedDelay(::sweepAbandoned, sweep, sweep, TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    fun stopHeartbeats() {
        heartbeats.shutdownNow()
        sweeper.shutdownNow()
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

    /**
     * A dead instance cannot report its own Test Runs, so this instance does: a record that still says RUNNING with a stale
     * heartbeat, and that this instance is not running, is stored as ABANDONED and its callback is sent once.
     */
    private fun sweepAbandoned() {
        try {
            for (ref in historyStore.listRunning()) {
                if (active.containsKey(ref.testRunId) || !isAbandoned(RunStatus.RUNNING, ref.heartbeatAt)) continue
                val record = results.read(ref.pipeline, ref.testRunId) ?: continue
                if (!isAbandoned(record.status, record.heartbeatAt)) continue
                val abandoned = reportAbandoned(record).copy(
                    finishedAt = now(),
                    callback = record.callbackUrl?.let { CallbackResult("PENDING", 0) },
                )
                results.write(abandoned)
                log.warn("Test Run {} of Pipeline {} was abandoned", ref.testRunId, ref.pipeline)
                webhook.notify(abandoned)
            }
        } catch (e: Exception) {
            log.warn("Sweep for abandoned Test Runs failed", e)
        }
    }

    fun trigger(request: TriggerRequest): RunRecord {
        // Checked first, so a caller that will be turned away costs no metadata queries against the Environments.
        // DuckDB memory and temp disk are sized for the cap, so a trigger beyond it is turned away, not queued (ADR 0004).
        if (!slots.tryAcquire()) {
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "The service is at capacity (${props.maxConcurrentRuns} Test Runs in progress). No Test Run was created; try again later.",
                headers = mapOf("Retry-After" to props.retryAfter.seconds.toString()),
            )
        }
        val run: ActiveRun
        try {
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
                callbackUrl = request.callbackUrl,
            )
            run = ActiveRun(record, results)
            run.start()
            active[record.testRunId] = run
            executor.execute { execute(run) }
        } catch (e: Throwable) {
            slots.release()
            throw e
        }
        return run.record
    }

    private fun newWorkspace(testRunId: String) =
        Workspace(Path.of(props.duckdb.tempDirectory).resolve(testRunId), props.duckdb.memoryLimit, props.duckdb.threads)

    fun get(pipeline: String, testRunId: String): RunRecord {
        val record = results.read(pipeline, testRunId) ?: throw ApiException(HttpStatus.NOT_FOUND, "Test Run $testRunId not found")
        // A Test Run this instance is running is alive, even if writing its heartbeat to MinIO has been failing.
        return if (active.containsKey(testRunId)) record else reportAbandoned(record)
    }

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
        var finished: RunRecord? = null
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
            val stored = final.copy(
                status = RunStatus.COMPLETED, finishedAt = now(), heartbeatAt = now(),
                callback = started.callbackUrl?.let { CallbackResult("PENDING", 0) },
            )
            run.finish(stored)
            finished = stored
        } catch (e: Exception) {
            log.error("Could not store the result of Test Run {}", started.testRunId, e)
        } finally {
            active.remove(started.testRunId)
            slots.release()
        }
        finished?.let(webhook::notify)
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
