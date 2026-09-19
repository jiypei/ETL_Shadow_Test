package com.etlshadowtest.run

import com.etlshadowtest.results.ResultsStore

/**
 * A Test Run this instance is executing. All writes of its record go through here, one at a time, so a heartbeat
 * can never overwrite the final result and the record in MinIO always has a single writer (ADR 0002).
 */
class ActiveRun(record: RunRecord, private val results: ResultsStore) {
    @Volatile
    var record: RunRecord = record
        private set

    private var finished = false

    @Synchronized
    fun start() = results.write(record)

    @Synchronized
    fun heartbeat() {
        if (finished) return
        record = record.copy(heartbeatAt = now())
        results.write(record)
    }

    @Synchronized
    fun finish(final: RunRecord) {
        finished = true
        record = final
        results.write(final)
    }
}
