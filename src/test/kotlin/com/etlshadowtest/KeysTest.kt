package com.etlshadowtest

import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KeysTest : ShadowTestBase() {
    private fun seed(stagingRows: List<List<Any?>>, productionRows: List<List<Any?>>): String {
        val table = uniqueName("KEYS")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, NAME VARCHAR2(20)")
        staging.insert(table, listOf("ID", "NAME"), *stagingRows.toTypedArray())
        production.insert(table, listOf("ID", "NAME"), *productionRows.toTypedArray())
        return table
    }

    private fun run(table: String, extra: Map<String, Any?> = emptyMap()) =
        api.run(shadowRequest(uniqueName("p"), oracleTarget("t", table, extra = extra))).body!!

    private val JsonNode.rowDiff: JsonNode get() = this["targets"][0]["rowDiff"]

    @Test
    fun `duplicate keys on the Staging side give FAIL and the duplicated keys are reported`() {
        val done = run(seed(listOf(listOf(1, "a"), listOf(1, "a"), listOf(2, "b")), listOf(listOf(1, "a"), listOf(2, "b"))))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        val duplicates = done.rowDiff["duplicateKeys"]
        assertThat(duplicates["staging"]["keys"].asLong()).isEqualTo(1)
        assertThat(duplicates["staging"]["sample"][0]["key"]["ID"].asInt()).isEqualTo(1)
        assertThat(duplicates["staging"]["sample"][0]["occurrences"].asLong()).isEqualTo(2)
        assertThat(duplicates["production"]["keys"].asLong()).isEqualTo(0)
    }

    @Test
    fun `duplicate keys on the Production side give FAIL and the duplicated keys are reported`() {
        val done = run(seed(listOf(listOf(1, "a"), listOf(2, "b")), listOf(listOf(1, "a"), listOf(2, "b"), listOf(2, "b"), listOf(2, "c"))))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        val duplicates = done.rowDiff["duplicateKeys"]
        assertThat(duplicates["production"]["keys"].asLong()).isEqualTo(1)
        assertThat(duplicates["production"]["sample"][0]["key"]["ID"].asInt()).isEqualTo(2)
        assertThat(duplicates["production"]["sample"][0]["occurrences"].asLong()).isEqualTo(3)
        assertThat(duplicates["staging"]["keys"].asLong()).isEqualTo(0)
    }

    @Test
    fun `identical duplicated data on both sides is still a FAIL because a key must identify rows`() {
        val rows = listOf(listOf<Any?>(1, "a"), listOf<Any?>(1, "a"))
        val done = run(seed(rows, rows))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        val check = done["targets"][0]["aggregateCheck"]["checks"].first { it["check"].asText() == "duplicate_key_rows" }
        assertThat(check["agrees"].asBoolean()).isFalse()
        assertThat(done.rowDiff["duplicateKeys"]["staging"]["keys"].asLong()).isEqualTo(1)
        assertThat(done.rowDiff["duplicateKeys"]["production"]["keys"].asLong()).isEqualTo(1)
    }

    @Test
    fun `a duplicated key is reported as a duplicate, not as a key that exists on one side only`() {
        val done = run(seed(listOf(listOf(1, "a"), listOf(1, "a"), listOf(2, "b")), listOf(listOf(1, "a"), listOf(2, "b"))))
        assertThat(done.rowDiff["onlyInStaging"].asLong()).isEqualTo(0)
        assertThat(done.rowDiff["onlyInProduction"].asLong()).isEqualTo(0)
        assertThat(done.rowDiff["differentRows"].asLong()).isEqualTo(0)
    }

    @Test
    fun `a keyless Target with identical data gives PASS`() {
        val rows = listOf(listOf<Any?>(1, "a"), listOf<Any?>(1, "a"), listOf<Any?>(2, "b"))
        val done = run(seed(rows, rows.reversed()), extra = mapOf("keyless" to true))
        assertThat(done["verdict"].asText()).isEqualTo("PASS")
    }

    @Test
    fun `a keyless Target where a row's content differs gives FAIL and shows count differences of identical rows`() {
        val stagingRows = listOf(listOf<Any?>(1, "a"), listOf<Any?>(1, "a"), listOf<Any?>(2, "b"))
        val productionRows = listOf(listOf<Any?>(1, "a"), listOf<Any?>(2, "b"), listOf<Any?>(2, "c"))
        val done = run(seed(stagingRows, productionRows), extra = mapOf("keyless" to true))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        val diff = done.rowDiff["countDifferences"]
        assertThat(diff["differingRowContents"].asLong()).isEqualTo(2)
        assertThat(diff["rowsExtraInStaging"].asLong()).isEqualTo(1)
        assertThat(diff["rowsExtraInProduction"].asLong()).isEqualTo(1)
        assertThat(diff["sample"].map { it["staging"].asLong() to it["production"].asLong() }).containsExactlyInAnyOrder(2L to 1L, 0L to 1L)
    }

    @Test
    fun `a keyless Target's report does not claim to identify rows and says why`() {
        val done = run(seed(listOf(listOf(1, "a")), listOf(listOf(1, "b"))), extra = mapOf("keyless" to true))
        assertThat(done.rowDiff["mismatchesFile"].isNull).isTrue()
        assertThat(done.rowDiff["note"].asText()).contains("no key").contains("cannot identify")
    }

    @Test
    fun `a Target that is neither keyless nor given key columns is rejected with a clear error`() {
        val table = seed(listOf(listOf(1, "a")), listOf(listOf(1, "a")))
        val response = api.trigger(shadowRequest(uniqueName("p"), oracleTarget("orders", table, extra = mapOf("keyColumns" to emptyList<String>()))))
        assertThat(response.status).isEqualTo(400)
        assertThat(response.raw).contains("orders").contains("keyColumns").contains("keyless")
    }

    @Test
    fun `a keyless Target that also names key columns is rejected`() {
        val table = seed(listOf(listOf(1, "a")), listOf(listOf(1, "a")))
        val response = api.trigger(shadowRequest(uniqueName("p"), oracleTarget("orders", table, extra = mapOf("keyless" to true, "keyColumns" to listOf("ID")))))
        assertThat(response.status).isEqualTo(400)
        assertThat(response.raw).contains("orders").contains("keyless")
    }
}
