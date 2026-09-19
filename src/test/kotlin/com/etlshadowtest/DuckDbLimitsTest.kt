package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** DuckDB is given less memory than the Row Diff over 1.5M rows needs, so it has to spill to the temporary directory. */
@TestPropertySource(properties = ["shadow.duckdb.memory-limit=160MB"])
class DuckDbLimitsTest : ShadowTestBase() {
    @Test
    fun `a Row Diff that exceeds the DuckDB memory limit spills to the temporary directory instead of failing`() {
        val table = uniqueName("BIG")
        for (db in listOf(staging, production)) {
            db.createTable(table, "ID NUMBER, PAYLOAD VARCHAR2(60)")
            db.execute("INSERT /*+ APPEND */ INTO $table SELECT LEVEL, RPAD('row' || LEVEL, 50, 'x') FROM DUAL CONNECT BY LEVEL <= 1500000")
        }
        // Every 100th row differs, so the Aggregate Check disagrees and the Row Diff has to join both 1.5M-row sides.
        production.update("UPDATE $table SET PAYLOAD = 'changed' WHERE MOD(ID, 100) = 0")

        val spilled = AtomicLong()
        val running = AtomicBoolean(true)
        val watcher = thread(isDaemon = true) {
            while (running.get()) {
                runCatching {
                    Files.walk(TestEnvironment.duckdbTempDirectory).use { paths ->
                        val bytes = paths.filter { it.toString().contains("/spill/") && Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
                        spilled.accumulateAndGet(bytes, ::maxOf)
                    }
                }
                Thread.sleep(20)
            }
        }
        val done = try {
            api.run(shadowRequest(uniqueName("p"), oracleTarget("big", table)))
        } finally {
            running.set(false)
            watcher.join()
        }
        assertThat(spilled.get()).describedAs("bytes DuckDB wrote to the configured temporary directory").isGreaterThan(0)
        assertThat(done["verdict"]!!.asText()).isEqualTo("FAIL")
        val rowDiff = done["targets"]!![0]["rowDiff"]
        assertThat(rowDiff["status"].asText()).isEqualTo("RAN")
        assertThat(rowDiff["differentRows"].asLong()).isEqualTo(15000)
        assertThat(MinioFixtures.readMismatches(rowDiff["mismatchesFile"].asText())).hasSize(100)
    }
}
