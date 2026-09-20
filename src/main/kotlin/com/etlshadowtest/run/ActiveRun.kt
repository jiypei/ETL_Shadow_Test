package com.etlshadowtest.run

import com.etlshadowtest.results.ResultsStore

/**
 * A Test Run this instance is executing. Every write of its record while it runs goes through here, one at a time,
 * so a heartbeat can never overwrite the final result. After it ends, only the callback outcome (WebhookNotifier)
 * is added to its record, and the abandoned sweep only touches records that no instance is running (ADR 0006).
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
