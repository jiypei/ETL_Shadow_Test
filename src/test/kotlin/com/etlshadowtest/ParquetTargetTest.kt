package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ParquetFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.parquetTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class ParquetTargetTest : ShadowTestBase() {
    private val mapper = ObjectMapper()
    private val columns = "ID BIGINT, NAME VARCHAR, AMOUNT DECIMAL(12,2), DAY DATE, LOADED TIMESTAMP, NOTE VARCHAR"

    private fun row(id: Long, name: String? = "n$id", amount: String? = "1.50", day: String = "2026-01-10", note: String? = null, loaded: LocalDateTime = LocalDateTime.of(2026, 2, 1, 0, 0, 1)) =
        listOf<Any?>(id, name, amount?.let(::BigDecimal), LocalDate.parse(day), loaded, note)

    private fun seed(stagingRows: List<List<Any?>>, productionRows: List<List<Any?>>): String {
        val path = uniqueName("events").lowercase()
        ParquetFixtures.staging(path, columns, stagingRows)
        ParquetFixtures.production(path, columns, productionRows)
        return path
    }

    private fun run(path: String, extra: Map<String, Any?> = emptyMap(), scope: Map<String, Any?>? = null): JsonNode =
        api.run(shadowRequest(uniqueName("p"), parquetTarget("events", path, extra = extra), scope = scope)).body!!

    @Test
    fun `identical Parquet data in both Environments gives PASS`() {
        val rows = listOf(row(1), row(2, note = "x"), row(3, name = null, amount = null))
        val done = run(seed(rows, rows))
        assertThat(done["verdict"].asText()).isEqualTo("PASS")
        assertThat(done["targets"][0]["rowDiff"]["status"].asText()).isEqualTo("SKIPPED")
    }

    @Test
    fun `differing Parquet data gives FAIL with sampled Mismatches showing values from both Environments`() {
        val done = run(seed(listOf(row(1), row(2, name = "beta")), listOf(row(1), row(2, name = "beta-prod"), row(3))))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        val rowDiff = done["targets"][0]["rowDiff"]
        assertThat(rowDiff["differentRows"].asLong()).isEqualTo(1)
        assertThat(rowDiff["onlyInProduction"].asLong()).isEqualTo(1)

        val byType = MinioFixtures.readMismatches(rowDiff["mismatchesFile"].asText()).associateBy { it["mismatch_type"] }
        val different = byType.getValue("DIFFERENT")
        assertThat(mapper.readTree(different["key"] as String)["ID"].asInt()).isEqualTo(2)
        assertThat(mapper.readTree(different["staging"] as String)["NAME"].asText()).isEqualTo("beta")
        assertThat(mapper.readTree(different["production"] as String)["NAME"].asText()).isEqualTo("beta-prod")
        assertThat(mapper.readTree(byType.getValue("ONLY_IN_PRODUCTION")["production"] as String)["NAME"].asText()).isEqualTo("n3")
    }

    @Test
    fun `the Aggregate Check detects each kind of difference in Parquet data`() {
        fun failing(stagingRow: List<Any?>, productionRow: List<Any?>): List<String> {
            val done = run(seed(listOf(stagingRow), listOf(productionRow)))
            assertThat(done["verdict"].asText()).isEqualTo("FAIL")
            return done["targets"][0]["aggregateCheck"]["checks"].filter { !it["agrees"].asBoolean() }.map { it["check"].asText() }
        }
        assertThat(failing(row(1, amount = "1.50"), row(1, amount = "1.51"))).contains("sum", "checksum")
        assertThat(failing(row(1, note = null), row(1, note = "x"))).contains("null_count")
        assertThat(failing(row(1, name = "alpha"), row(1, name = "alpha "))).containsExactly("checksum")
        assertThat(failing(row(1, loaded = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 1_000)), row(1, loaded = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 2_000)))).containsExactly("checksum")
    }

    @Test
    fun `NULL equals NULL and an empty string is not NULL`() {
        val same = run(seed(listOf(row(1, name = null, amount = null)), listOf(row(1, name = null, amount = null))))
        assertThat(same["verdict"].asText()).isEqualTo("PASS")
        val different = run(seed(listOf(row(1, note = null)), listOf(row(1, note = ""))))
        assertThat(different["verdict"].asText()).isEqualTo("FAIL")
    }

    @Test
    fun `a dataset made of several files in nested directories is compared as one dataset`() {
        val path = uniqueName("multi").lowercase()
        ParquetFixtures.staging("$path/sub1", columns, listOf(row(1)), "a.parquet")
        ParquetFixtures.staging("$path/sub2", columns, listOf(row(2)), "b.parquet")
        ParquetFixtures.production(path, columns, listOf(row(1), row(2)))
        assertThat(run(path)["verdict"].asText()).isEqualTo("PASS")
    }

    @Test
    fun `Comparison Scope applies to Parquet Targets`() {
        val stagingRows = listOf(row(1, day = "2026-01-10"), row(2, day = "2026-03-01"))
        val productionRows = listOf(row(1, day = "2026-01-10"), row(3, day = "2026-04-01"))
        val path = seed(stagingRows, productionRows)
        val january = mapOf("from" to "2026-01-01", "to" to "2026-02-01")
        val done = run(path, extra = mapOf("scopeColumn" to "DAY"), scope = january)
        assertThat(done["verdict"].asText()).isEqualTo("PASS")

        val wide = run(path, extra = mapOf("scopeColumn" to "DAY"), scope = mapOf("from" to "2026-01-01", "to" to "2026-12-31"))
        assertThat(wide["verdict"].asText()).isEqualTo("FAIL")
    }

    @Test
    fun `ignored columns work for Parquet Targets`() {
        val path = seed(listOf(row(1, loaded = LocalDateTime.of(2026, 5, 1, 0, 0))), listOf(row(1, loaded = LocalDateTime.of(2026, 6, 1, 0, 0))))
        assertThat(run(path, extra = mapOf("ignoredColumns" to listOf("LOADED")))["verdict"].asText()).isEqualTo("PASS")
        assertThat(run(path)["verdict"].asText()).isEqualTo("FAIL")
    }

    @Test
    fun `duplicate keys are a FAIL for Parquet Targets`() {
        val rows = listOf(row(1), row(1))
        val done = run(seed(rows, rows))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done["targets"][0]["rowDiff"]["duplicateKeys"]["staging"]["keys"].asLong()).isEqualTo(1)
    }

    @Test
    fun `keyless Parquet Targets are compared by counts of identical rows`() {
        val same = listOf(row(1), row(1), row(2))
        assertThat(run(seed(same, same.reversed()), extra = mapOf("keyless" to true))["verdict"].asText()).isEqualTo("PASS")
        val done = run(seed(listOf(row(1), row(1)), listOf(row(1))), extra = mapOf("keyless" to true))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done["targets"][0]["rowDiff"]["countDifferences"]["rowsExtraInStaging"].asLong()).isEqualTo(1)
    }

    @Test
    fun `a Parquet Target whose location is missing in one Environment is not silently treated as empty`() {
        val path = uniqueName("only-staging").lowercase()
        ParquetFixtures.staging(path, columns, emptyList())
        val done = run(path)
        assertThat(done["verdict"].asText()).isEqualTo("ERROR")
        assertThat(done["targets"][0]["reason"].asText()).contains("Production")

        val missingInStaging = uniqueName("only-production").lowercase()
        ParquetFixtures.production(missingInStaging, columns, listOf(row(1)))
        val other = run(missingInStaging)
        assertThat(other["verdict"].asText()).isEqualTo("ERROR")
        assertThat(other["targets"][0]["reason"].asText()).contains("Staging")
    }

    @Test
    fun `a Parquet location that does not exist in either Environment is rejected up front`() {
        val response = api.trigger(shadowRequest(uniqueName("p"), parquetTarget("events", uniqueName("nowhere").lowercase())))
        assertThat(response.status).isEqualTo(400)
        assertThat(response.raw).contains("events")
    }

    @Test
    fun `an Oracle location on a Parquet Target and a path on an Oracle Target are rejected`() {
        val bad = parquetTarget("events", "x") + mapOf("staging" to mapOf("schema" to "S", "table" to "T"))
        assertThat(api.trigger(shadowRequest(uniqueName("p"), bad)).status).isEqualTo(400)
    }
}
