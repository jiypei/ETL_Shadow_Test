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

class VerdictTest : ShadowTestBase() {
    private fun table(stagingRows: Int, productionRows: Int?): String {
        val table = uniqueName("V")
        staging.createTable(table, "ID NUMBER")
        staging.insert(table, listOf("ID"), *(1..stagingRows).map { listOf<Any?>(it) }.toTypedArray())
        if (productionRows != null) {
            production.createTable(table, "ID NUMBER")
            production.insert(table, listOf("ID"), *(1..productionRows).map { listOf<Any?>(it) }.toTypedArray())
        }
        return table
    }

    private fun passing(name: String) = oracleTarget(name, table(2, 2))
    private fun failing(name: String) = oracleTarget(name, table(2, 3))

    /** A Target that exists in Staging only, so it cannot be compared. */
    private fun missingInProduction(name: String, extra: Map<String, Any?> = emptyMap()) = oracleTarget(name, table(2, null), extra = extra)

    private fun run(vararg targets: Map<String, Any?>): JsonNode = api.run(shadowRequest(uniqueName("p"), *targets)).body!!

    private fun JsonNode.verdicts(): Map<String, String> = this["targets"].associate { it["name"].asText() to it["verdict"].asText() }

    @Test
    fun `a Test Run over several Targets reports a Verdict per Target`() {
        val done = run(passing("a"), failing("b"), missingInProduction("c"))
        assertThat(done.verdicts()).containsExactlyInAnyOrderEntriesOf(mapOf("a" to "PASS", "b" to "FAIL", "c" to "ERROR"))
    }

    @Test
    fun `the Pipeline Verdict is FAIL when any Target fails even if another is ERROR`() {
        assertThat(run(passing("a"), failing("b"), missingInProduction("c"))["verdict"].asText()).isEqualTo("FAIL")
    }

    @Test
    fun `the Pipeline Verdict is ERROR when no Target fails and at least one is ERROR`() {
        assertThat(run(passing("a"), missingInProduction("c"))["verdict"].asText()).isEqualTo("ERROR")
    }

    @Test
    fun `the Pipeline Verdict is PASS only when every counted Target passes`() {
        assertThat(run(passing("a"), passing("b"))["verdict"].asText()).isEqualTo("PASS")
    }

    @Test
    fun `a Target missing in Production without the New Target flag is ERROR`() {
        val done = run(missingInProduction("orders"))
        assertThat(done["verdict"].asText()).isEqualTo("ERROR")
        assertThat(done["targets"][0]["verdict"].asText()).isEqualTo("ERROR")
        assertThat(done["targets"][0]["reason"].asText()).contains("missing in Production")
    }

    @Test
    fun `a Target missing in Staging is ERROR`() {
        val table = uniqueName("V")
        production.createTable(table, "ID NUMBER")
        val done = run(oracleTarget("orders", table))
        assertThat(done["verdict"].asText()).isEqualTo("ERROR")
        assertThat(done["targets"][0]["reason"].asText()).contains("missing in Staging")
    }

    @Test
    fun `a New Target is SKIPPED, does not affect the Pipeline Verdict and is listed as unverified`() {
        val done = run(passing("a"), missingInProduction("fresh", extra = mapOf("newTarget" to true)))
        assertThat(done.verdicts()).containsEntry("fresh", "SKIPPED").containsEntry("a", "PASS")
        assertThat(done["verdict"].asText()).isEqualTo("PASS")
        assertThat(done["unverifiedTargets"].map { it.asText() }).containsExactly("fresh")
        assertThat(done["targets"].first { it["name"].asText() == "fresh" }["reason"].asText()).contains("New Target").contains("unverified")
    }

    @Test
    fun `a New Target does not hide a failing Target`() {
        val done = run(failing("b"), missingInProduction("fresh", extra = mapOf("newTarget" to true)))
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
    }

    @Test
    fun `a Pipeline whose Targets are all SKIPPED does not report PASS`() {
        val done = run(missingInProduction("fresh", extra = mapOf("newTarget" to true)))
        assertThat(done["verdict"].asText()).isEqualTo("SKIPPED")
        assertThat(done["unverifiedTargets"].map { it.asText() }).containsExactly("fresh")
    }

    @Test
    fun `a Target declared New that turns out to exist in Production is still skipped, not compared`() {
        val done = run(oracleTarget("fresh", table(2, 9), extra = mapOf("newTarget" to true)))
        assertThat(done.verdicts()).containsEntry("fresh", "SKIPPED")
    }

    @Test
    fun `a Target that exists in neither Environment is rejected as a typo rather than reported as ERROR`() {
        val response = api.trigger(shadowRequest(uniqueName("p"), oracleTarget("orders", uniqueName("NOPE"))))
        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun `Parquet and Oracle Targets are combined in one Pipeline Verdict`() {
        val path = uniqueName("mix").lowercase()
        ParquetFixtures.staging(path, "ID BIGINT", listOf(listOf(1L)))
        ParquetFixtures.production(path, "ID BIGINT", listOf(listOf(1L)))
        val done = run(passing("oracle"), parquetTarget("parquet", path))
        assertThat(done.verdicts()).containsEntry("oracle", "PASS").containsEntry("parquet", "PASS")
    }
}
