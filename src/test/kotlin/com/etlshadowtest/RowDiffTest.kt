package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment
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
import java.nio.file.Files
import kotlin.io.path.exists

class RowDiffTest : ShadowTestBase() {
    private val mapper = ObjectMapper()
    private val names = listOf("ID", "NAME", "AMOUNT", "NOTE")

    private fun row(id: Int, name: String? = "n$id", amount: String? = "1.50", note: String? = null) =
        listOf<Any?>(id, name, amount?.let(::BigDecimal), note)

    private fun seed(stagingRows: List<List<Any?>>, productionRows: List<List<Any?>>): String {
        val table = uniqueName("RD")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, NAME VARCHAR2(50), AMOUNT NUMBER(10,2), NOTE VARCHAR2(20)")
        staging.insert(table, names, *stagingRows.toTypedArray())
        production.insert(table, names, *productionRows.toTypedArray())
        return table
    }

    private class Outcome(val pipeline: String, val response: JsonNode) {
        val target: JsonNode get() = response["targets"][0]
        val rowDiff: JsonNode get() = target["rowDiff"]
        val verdict: String get() = response["verdict"].asText()
    }

    private fun run(table: String, extra: Map<String, Any?> = emptyMap()): Outcome {
        val pipeline = uniqueName("p")
        val done = api.run(shadowRequest(pipeline, oracleTarget("t", table, extra = extra)))
        return Outcome(pipeline, done.body!!).also { it.response.let { r -> r["testRunId"] } }
    }

    private fun Outcome.mismatches(): List<Map<String, Any?>> = MinioFixtures.readMismatches(rowDiff["mismatchesFile"].asText())

    private fun Map<String, Any?>.json(field: String): JsonNode? = (this[field] as String?)?.let(mapper::readTree)

    @Test
    fun `differing rows give FAIL and the sample holds the key and the values from both Environments`() {
        val out = run(seed(listOf(row(1), row(2, name = "beta")), listOf(row(1), row(2, name = "beta-prod"))))
        assertThat(out.verdict).isEqualTo("FAIL")
        assertThat(out.rowDiff["status"].asText()).isEqualTo("RAN")
        assertThat(out.rowDiff["differentRows"].asLong()).isEqualTo(1)

        val sample = out.mismatches().single()
        assertThat(sample["mismatch_type"]).isEqualTo("DIFFERENT")
        assertThat(sample.json("key")!!["ID"].decimalValue()).isEqualByComparingTo("2")
        assertThat(sample.json("staging")!!["NAME"].asText()).isEqualTo("beta")
        assertThat(sample.json("production")!!["NAME"].asText()).isEqualTo("beta-prod")
        assertThat(sample.json("differing_columns")!!.map { it.asText() }).containsExactly("NAME")
    }

    @Test
    fun `keys that exist only in Staging and keys that exist only in Production are both reported`() {
        val out = run(seed(listOf(row(1), row(2)), listOf(row(1), row(3))))
        assertThat(out.verdict).isEqualTo("FAIL")
        assertThat(out.rowDiff["onlyInStaging"].asLong()).isEqualTo(1)
        assertThat(out.rowDiff["onlyInProduction"].asLong()).isEqualTo(1)
        assertThat(out.rowDiff["differentRows"].asLong()).isEqualTo(0)

        val byType = out.mismatches().associateBy { it["mismatch_type"] }
        val onlyStaging = byType.getValue("ONLY_IN_STAGING")
        assertThat(onlyStaging.json("key")!!["ID"].asInt()).isEqualTo(2)
        assertThat(onlyStaging.json("staging")!!["NAME"].asText()).isEqualTo("n2")
        assertThat(onlyStaging["production"]).isNull()
        val onlyProduction = byType.getValue("ONLY_IN_PRODUCTION")
        assertThat(onlyProduction.json("key")!!["ID"].asInt()).isEqualTo(3)
        assertThat(onlyProduction.json("production")!!["NAME"].asText()).isEqualTo("n3")
        assertThat(onlyProduction["staging"]).isNull()
    }

    @Test
    fun `the Row Fingerprint uses the same normalization as the Aggregate Check`() {
        val stagingRows = listOf(row(1, note = null, amount = null), row(2, name = "padded"), row(3, name = "same", amount = "2.00"))
        val productionRows = listOf(row(1, note = null, amount = null), row(2, name = "padded "), row(3, name = "same", amount = "2"))
        val out = run(seed(stagingRows, productionRows))
        assertThat(out.verdict).isEqualTo("FAIL")
        assertThat(out.rowDiff["differentRows"].asLong()).isEqualTo(1)
        assertThat(out.mismatches().single().json("key")!!["ID"].asInt()).isEqualTo(2)
    }

    @Test
    fun `a Target whose Aggregate Check agrees does not run a Row Diff and the record says so`() {
        val rows = listOf(row(1), row(2))
        val pipeline = uniqueName("p")
        val done = api.run(shadowRequest(pipeline, oracleTarget("t", seed(rows, rows))))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")

        val record = MinioFixtures.readRunJson(pipeline, done.testRunId)
        assertThat(record["targets"][0]["rowDiff"]["status"].asText()).isEqualTo("SKIPPED")
        assertThat(record["targets"][0]["rowDiff"]["reason"].asText()).contains("Aggregate Check agreed")
        assertThat(MinioFixtures.list(TestEnvironment.RESULTS_BUCKET, "results/$pipeline/${done.testRunId}/mismatches/")).isEmpty()
    }

    @Test
    fun `the Test Run record links to the sampled Mismatches file`() {
        val pipeline = uniqueName("p")
        val done = api.run(shadowRequest(pipeline, oracleTarget("orders", seed(listOf(row(1)), listOf(row(1, name = "x"))))))
        val record = MinioFixtures.readRunJson(pipeline, done.testRunId)
        val file = record["targets"][0]["rowDiff"]["mismatchesFile"].asText()
        assertThat(file).isEqualTo("results/$pipeline/${done.testRunId}/mismatches/orders.parquet")
        assertThat(MinioFixtures.exists(TestEnvironment.RESULTS_BUCKET, file)).isTrue()
    }

    @Test
    fun `each Test Run's DuckDB working space is removed when the Test Run ends`() {
        val out = run(seed(listOf(row(1), row(2)), listOf(row(1), row(3))))
        assertThat(out.verdict).isEqualTo("FAIL")
        val leftovers = Files.list(TestEnvironment.duckdbTempDirectory).use { it.toList() }
        assertThat(leftovers).noneMatch { it.exists() && it.fileName.toString() == out.response["testRunId"].asText() }
    }

    @Test
    fun `by default at most 100 Mismatches per type are sampled while all of them are counted`() {
        val stagingRows = (1..120).map { row(it, name = "staging") }
        val productionRows = (1..120).map { row(it, name = "production") }
        val out = run(seed(stagingRows, productionRows))
        assertThat(out.rowDiff["differentRows"].asLong()).isEqualTo(120)
        assertThat(out.rowDiff["sampleLimitPerType"].asInt()).isEqualTo(100)
        assertThat(out.rowDiff["sampled"].asInt()).isEqualTo(100)
        assertThat(out.mismatches()).hasSize(100)
    }
}
