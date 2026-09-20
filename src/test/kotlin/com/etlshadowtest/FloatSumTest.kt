package com.etlshadowtest

import com.etlshadowtest.support.ParquetFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.parquetTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Adding IEEE floating-point numbers is not associative, so a sum over the same values can differ with row order.
 * The exact per-value checksum is what decides equality for such columns; their sum is reported for information only.
 */
class FloatSumTest : ShadowTestBase() {
    // The same four values; summed in stored order they give 1.0 in one order and 2.0 in the other.
    private val cancelling = listOf(1e16, 1.0, -1e16, 1.0)
    private val stagingOrder = listOf(0, 1, 2, 3)
    private val productionOrder = listOf(0, 2, 1, 3)

    private fun oracleTable(stagingValues: List<Double>, productionValues: List<Double>, type: String = "BINARY_DOUBLE"): String {
        val table = uniqueName("FLT")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, V $type")
        staging.insert(table, listOf("ID", "V"), *stagingValues.mapIndexed { i, v -> listOf<Any?>(i + 1, v) }.toTypedArray())
        production.insert(table, listOf("ID", "V"), *productionValues.mapIndexed { i, v -> listOf<Any?>(i + 1, v) }.toTypedArray())
        return table
    }

    private fun run(target: Map<String, Any?>): JsonNode = api.run(shadowRequest(uniqueName("p"), target)).body!!

    private fun JsonNode.check(check: String, column: String): JsonNode =
        this["targets"][0]["aggregateCheck"]["checks"].first { it["check"].asText() == check && it["column"].asText() == column }

    private fun JsonNode.decisiveDisagreements(): List<String> =
        this["targets"][0]["aggregateCheck"]["checks"].filter { !it["agrees"].asBoolean() && it["decisive"].asBoolean() }.map { it["check"].asText() }

    @Test
    fun `the same Oracle floating-point values stored in a different order pass even though their sums differ in the last bits`() {
        // Rows differ in which ID carries which value, so the fixture keeps ID and value together while changing storage order.
        val table = uniqueName("FLT")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, V BINARY_DOUBLE")
        staging.insert(table, listOf("ID", "V"), *stagingOrder.map { listOf<Any?>(it + 1, cancelling[it]) }.toTypedArray())
        production.insert(table, listOf("ID", "V"), *productionOrder.map { listOf<Any?>(it + 1, cancelling[it]) }.toTypedArray())

        val done = run(oracleTarget("t", table))
        assertThat(done["verdict"].asText()).isEqualTo("PASS")
        val sum = done.check("sum", "V")
        assertThat(sum["method"].asText()).isEqualTo("informational")
        assertThat(sum["decisive"].asBoolean()).isFalse()
        assertThat(sum["staging"].decimalValue()).describedAs("the order-dependent sums really differ").isNotEqualByComparingTo(sum["production"].decimalValue())
    }

    @Test
    fun `a difference of one bit in a floating-point value is still a FAIL, found by the checksum and the Row Diff`() {
        val table = oracleTable(listOf(0.1, 0.2), listOf(0.1, Math.nextUp(0.2)))
        val done = run(oracleTarget("t", table))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.decisiveDisagreements()).contains("checksum")
        assertThat(done["targets"][0]["rowDiff"]["differentRows"].asLong()).isEqualTo(1)
    }

    @Test
    fun `decimal sums stay exact and decisive`() {
        val table = uniqueName("DEC")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, V NUMBER(20,10)")
        staging.insert(table, listOf("ID", "V"), listOf(1, java.math.BigDecimal("0.0000000001")))
        production.insert(table, listOf("ID", "V"), listOf(1, java.math.BigDecimal("0.0000000002")))
        val done = run(oracleTarget("t", table))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.check("sum", "V")["method"].asText()).isEqualTo("exact")
        assertThat(done.check("sum", "V")["decisive"].asBoolean()).isTrue()
    }

    @Test
    fun `Parquet DOUBLE columns behave the same way`() {
        val path = uniqueName("dbl").lowercase()
        val rows = { order: List<Int> -> order.map { listOf<Any?>(it + 1L, cancelling[it]) } }
        ParquetFixtures.staging(path, "ID BIGINT, V DOUBLE", rows(stagingOrder))
        ParquetFixtures.production(path, "ID BIGINT, V DOUBLE", rows(productionOrder))
        val same = run(parquetTarget("t", path))
        assertThat(same["verdict"].asText()).isEqualTo("PASS")
        assertThat(same.check("sum", "V")["decisive"].asBoolean()).isFalse()

        val differing = uniqueName("dbl").lowercase()
        ParquetFixtures.staging(differing, "ID BIGINT, V DOUBLE", listOf(listOf(1L, 0.1), listOf(2L, 0.2)))
        ParquetFixtures.production(differing, "ID BIGINT, V DOUBLE", listOf(listOf(1L, 0.1), listOf(2L, Math.nextUp(0.2))))
        val done = run(parquetTarget("t", differing))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.decisiveDisagreements()).contains("checksum")
        assertThat(done["targets"][0]["rowDiff"]["differentRows"].asLong()).isEqualTo(1)
    }

    @Test
    fun `a floating-point column with a tolerance is still checked by sum, min and max within the tolerance`() {
        val table = oracleTable(listOf(1.0, 2.0), listOf(1.004, 2.0))
        val done = run(oracleTarget("t", table, extra = mapOf("tolerances" to mapOf("V" to 0.01))))
        assertThat(done["verdict"].asText()).isEqualTo("PASS")
        assertThat(done.check("sum", "V")["method"].asText()).isEqualTo("tolerance")
        val beyond = run(oracleTarget("t", oracleTable(listOf(1.0, 2.0), listOf(1.5, 2.0)), extra = mapOf("tolerances" to mapOf("V" to 0.01))))
        assertThat(beyond["verdict"].asText()).isEqualTo("FAIL")
    }
}
