package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["shadow.mismatch-sample-size=2"])
class RowDiffSampleCapTest : ShadowTestBase() {
    @Test
    fun `the number of sampled Mismatches per type is capped by the configured limit`() {
        val table = uniqueName("CAP")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, NAME VARCHAR2(20)")
        staging.insert(table, listOf("ID", "NAME"), *(1..5).map { listOf<Any?>(it, "s") }.toTypedArray(), listOf(6, "only"), listOf(7, "only"), listOf(8, "only"))
        production.insert(table, listOf("ID", "NAME"), *(1..5).map { listOf<Any?>(it, "p") }.toTypedArray())

        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("t", table)))
        val rowDiff = done["targets"]!![0]["rowDiff"]
        assertThat(rowDiff["differentRows"].asLong()).isEqualTo(5)
        assertThat(rowDiff["onlyInStaging"].asLong()).isEqualTo(3)
        assertThat(rowDiff["sampleLimitPerType"].asInt()).isEqualTo(2)
        assertThat(rowDiff["sampled"].asInt()).isEqualTo(4)

        val samples = MinioFixtures.readMismatches(rowDiff["mismatchesFile"].asText())
        assertThat(samples.groupingBy { it["mismatch_type"] }.eachCount()).containsEntry("DIFFERENT", 2).containsEntry("ONLY_IN_STAGING", 2)
    }
}
