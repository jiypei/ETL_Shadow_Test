package com.etlshadowtest

import com.etlshadowtest.support.ParquetFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.parquetTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Production Oracle points at a closed port, as if its database were down. */
class UnreachableProductionTest : ShadowTestBase() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) = TestEnvironment.registerProperties(
            registry,
            mapOf(
                "shadow.production.oracle.url" to "jdbc:oracle:thin:@//127.0.0.1:1/FREEPDB1",
                "shadow.production.oracle.connection-timeout" to "2s",
            ),
        )
    }

    @Test
    fun `a source database that cannot be reached gives ERROR for the affected Targets and the other Targets still complete`() {
        val table = uniqueName("DOWN")
        staging.createTable(table, "ID NUMBER")
        val path = uniqueName("up").lowercase()
        ParquetFixtures.staging(path, "ID BIGINT", listOf(listOf(1L)))
        ParquetFixtures.production(path, "ID BIGINT", listOf(listOf(1L)))

        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("oracle", table), parquetTarget("parquet", path))).body!!
        val verdicts = done["targets"].associate { it["name"].asText() to it["verdict"].asText() }
        assertThat(verdicts).containsEntry("oracle", "ERROR").containsEntry("parquet", "PASS")
        assertThat(done["verdict"].asText()).isEqualTo("ERROR")
    }
}
