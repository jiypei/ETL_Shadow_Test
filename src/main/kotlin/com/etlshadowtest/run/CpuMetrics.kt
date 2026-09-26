package com.etlshadowtest.run

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory

/**
 * The service's CPU time split by who used it. DuckDB runs inside the JVM process, so there is no separate DuckDB
 * process to watch: its queries run partly on the Test Run thread that issued them and partly on DuckDB's own native
 * worker threads, which the JVM does not see. So:
 * - `test-runs`: Java threads named [RUN_THREAD] (comparison work, Oracle and MinIO reads, DuckDB work on the calling thread),
 * - `other-java`: every other Java thread (HTTP, heartbeats, sweeps, webhooks),
 * - `native`: the rest of the process: DuckDB worker threads, and the JVM's own GC and JIT threads.
 *
 * Each part only grows, as Prometheus counters must: a thread's time is added in deltas, so a thread that ends keeps what was counted.
 */
@Component
class CpuMetrics(registry: MeterRegistry) {
    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private val threads = ManagementFactory.getThreadMXBean()
    private val supported = os != null && threads.isThreadCpuTimeSupported

    private val lastByThread = HashMap<Long, Long>()
    private var testRunsNanos = 0L
    private var otherJavaNanos = 0L
    private var nativeNanos = 0L

    init {
        if (supported && !threads.isThreadCpuTimeEnabled) threads.isThreadCpuTimeEnabled = true
        if (supported) {
            for ((part, read) in listOf<Pair<String, () -> Long>>(
                "test-runs" to { testRunsNanos }, "other-java" to { otherJavaNanos }, "native" to { nativeNanos },
            )) {
                FunctionCounter.builder("shadow.process.cpu.time", this) { it.sample(); read() / 1e9 }
                    .description("CPU time used by the service process, split by kind of thread").baseUnit("seconds")
                    .tag("part", part).register(registry)
            }
        }
    }

    @Synchronized
    private fun sample() {
        val seen = HashSet<Long>()
        for (info in threads.getThreadInfo(threads.allThreadIds, 0)) {
            if (info == null) continue
            val cpu = threads.getThreadCpuTime(info.threadId).takeIf { it >= 0 } ?: continue
            seen.add(info.threadId)
            val delta = cpu - (lastByThread.put(info.threadId, cpu) ?: 0L)
            if (delta <= 0) continue
            if (info.threadName == RUN_THREAD) testRunsNanos += delta else otherJavaNanos += delta
        }
        lastByThread.keys.retainAll(seen)
        val rest = os!!.processCpuTime - testRunsNanos - otherJavaNanos
        if (rest > nativeNanos) nativeNanos = rest
    }

    companion object {
        /** The name of the threads that execute Test Runs. */
        const val RUN_THREAD = "test-run"
    }
}
