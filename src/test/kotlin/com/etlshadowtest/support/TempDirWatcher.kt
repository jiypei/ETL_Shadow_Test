package com.etlshadowtest.support

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Watches the service's DuckDB temporary directory while a Test Run executes. */
class TempDirWatcher(private val root: Path = TestEnvironment.duckdbTempDirectory) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val workspaceSeen = AtomicBoolean(false)
    private val spillBytes = AtomicLong()
    private val watcher = thread(isDaemon = true) {
        while (running.get()) {
            runCatching {
                Files.walk(root).use { paths ->
                    val all = paths.toList()
                    if (all.any { it.parent == root }) workspaceSeen.set(true)
                    val bytes = all.filter { it.toString().contains("/spill/") && Files.isRegularFile(it) }.sumOf { Files.size(it) }
                    spillBytes.accumulateAndGet(bytes, ::maxOf)
                }
            }
            Thread.sleep(10)
        }
    }

    /** True if a per-Test-Run working directory appeared while watching. */
    val sawWorkspace: Boolean get() = workspaceSeen.get()

    /** Most bytes of DuckDB spill files seen at once. */
    val peakSpillBytes: Long get() = spillBytes.get()

    fun leftovers(): List<Path> = Files.list(root).use { it.toList() }

    override fun close() {
        running.set(false)
        watcher.join()
    }
}
