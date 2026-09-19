package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ToleranceTest : ShadowTestBase() {
    private fun seed(stagingRows: List<List<Any?>>, productionRows: List<List<Any?>>): String {
        val table = uniqueName("TOL")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, PRICE NUMBER(12,4), COST NUMBER(12,4), NAME VARCHAR2(20)")
        staging.insert(table, listOf("ID", "PRICE", "COST", "NAME"), *stagingRows.toTypedArray())
        production.insert(table, listOf("ID", "PRICE", "COST", "NAME"), *productionRows.toTypedArray())
        return table
    }

    private fun row(id: Int, price: String, cost: String = "5", name: String = "n") = listOf<Any?>(id, BigDecimal(price), BigDecimal(cost), name)

    private fun run(table: String, tolerances: Map<String, Any>) =
        api.run(shadowRequest(uniqueName("p"), oracleTarget("t", table, extra = mapOf("tolerances" to tolerances)))).body!!

    private val price = mapOf("PRICE" to 0.01)

    @Test
    fun `a difference within the tolerance on a tolerance column gives PASS`() {
        val done = run(seed(listOf(row(1, "10.000"), row(2, "20.000")), listOf(row(1, "10.004"), row(2, "19.996"))), price)
        assertThat(done["verdict"].asText()).isEqualTo("PASS")
    }

    @Test
    fun `a difference beyond the tolerance that shows up in the Aggregate Check gives FAIL`() {
        val done = run(seed(listOf(row(1, "10.000"), row(2, "20.000")), listOf(row(1, "10.500"), row(2, "20.000"))), price)
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        val failing = done["targets"][0]["aggregateCheck"]["checks"].filter { !it["agrees"].asBoolean() }.map { it["check"].asText() + ":" + it["column"].asText() }
        assertThat(failing).contains("sum:PRICE", "min:PRICE").doesNotContain("max:PRICE")
    }

    @Test
    fun `a tolerance on one column does not relax any other column`() {
        val done = run(seed(listOf(row(1, "10.000", cost = "5.000")), listOf(row(1, "10.000", cost = "5.005"))), price)
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        val failing = done["targets"][0]["aggregateCheck"]["checks"].filter { !it["agrees"].asBoolean() }.map { it["column"].asText() }
        assertThat(failing).contains("COST").doesNotContain("PRICE")
    }

    @Test
    fun `tolerance columns are excluded from the Row Fingerprint`() {
        val stagingRows = listOf(row(1, "10.000"), row(2, "20.000", name = "x"))
        val productionRows = listOf(row(1, "10.500"), row(2, "20.000", name = "y"))
        val done = run(seed(stagingRows, productionRows), price)
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        val rowDiff = done["targets"][0]["rowDiff"]
        // Only row 2 differs in fingerprinted columns; the PRICE difference on row 1 is not part of the fingerprint.
        assertThat(rowDiff["differentRows"].asLong()).isEqualTo(1)
        val sample = MinioFixtures.readMismatches(rowDiff["mismatchesFile"].asText()).single()
        assertThat(ObjectMapper().readTree(sample["key"] as String)["ID"].asInt()).isEqualTo(2)
    }

    @Test
    fun `sampled Mismatches compare tolerance columns within the tolerance`() {
        val stagingRows = listOf(row(1, "10.000", name = "x"), row(2, "20.000", name = "x"))
        val productionRows = listOf(row(1, "10.004", name = "y"), row(2, "21.000", name = "y"))
        val done = run(seed(stagingRows, productionRows), price)
        val samples = MinioFixtures.readMismatches(done["targets"][0]["rowDiff"]["mismatchesFile"].asText())
        val differing = samples.associate {
            ObjectMapper().readTree(it["key"] as String)["ID"].asInt() to ObjectMapper().readTree(it["differing_columns"] as String).map { c -> c.asText() }
        }
        assertThat(differing.getValue(1)).containsExactly("NAME")
        assertThat(differing.getValue(2)).containsExactlyInAnyOrder("NAME", "PRICE")
    }

    @Test
    fun `the report states for each tolerance column how it was checked and what it would not catch`() {
        val done = run(seed(listOf(row(1, "10.000")), listOf(row(1, "10.001"))), price)
        val entry: JsonNode = done["targets"][0]["toleranceColumns"].single()
        assertThat(entry["column"].asText()).isEqualTo("PRICE")
        assertThat(entry["tolerance"].decimalValue()).isEqualByComparingTo("0.01")
        assertThat(entry["checkedBy"].asText()).contains("Aggregate Check").contains("sum").contains("min").contains("max").contains("not compared row by row")
        assertThat(entry["checkedBy"].asText()).contains("not detected")
        val check = done["targets"][0]["aggregateCheck"]["checks"].first { it["check"].asText() == "sum" && it["column"].asText() == "PRICE" }
        assertThat(check["method"].asText()).isEqualTo("tolerance")
        assertThat(check["tolerance"].decimalValue()).isEqualByComparingTo("0.01")
    }

    @Test
    fun `known gap by design - row level differences that leave the aggregates within tolerance are not caught`() {
        val done = run(seed(listOf(row(1, "10.000"), row(2, "20.000")), listOf(row(1, "20.000"), row(2, "10.000"))), price)
        assertThat(done["verdict"].asText()).isEqualTo("PASS")
        assertThat(done["targets"][0]["toleranceColumns"][0]["checkedBy"].asText()).contains("not detected")
    }

    @Test
    fun `a tolerance given for a column that is not numeric is rejected with a clear error`() {
        val table = seed(listOf(row(1, "1")), listOf(row(1, "1")))
        val response = api.trigger(shadowRequest(uniqueName("p"), oracleTarget("orders", table, extra = mapOf("tolerances" to mapOf("NAME" to 0.1)))))
        assertThat(response.status).isEqualTo(400)
        assertThat(response.raw).contains("orders").contains("NAME").contains("not numeric")
    }

    @Test
    fun `a negative tolerance is rejected`() {
        val table = seed(listOf(row(1, "1")), listOf(row(1, "1")))
        val response = api.trigger(shadowRequest(uniqueName("p"), oracleTarget("orders", table, extra = mapOf("tolerances" to mapOf("PRICE" to -0.1)))))
        assertThat(response.status).isEqualTo(400)
    }
}
