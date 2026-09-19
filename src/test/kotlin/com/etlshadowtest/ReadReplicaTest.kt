package com.etlshadowtest

import com.etlshadowtest.support.ShadowTestSupport
import com.etlshadowtest.support.TestEnvironment
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

private const val UNREACHABLE = "jdbc:oracle:thin:@//127.0.0.1:1/FREEPDB1"

private fun seedTable(): String {
    val table = uniqueName("REPL")
    for (db in listOf(staging, production)) { db.createTable(table, "ID NUMBER"); db.insert(table, listOf("ID"), listOf(1)) }
    return table
}

/** The primary is unreachable and only the read replica answers: Production queries must go to the replica. */
class ReadReplicaTest : ShadowTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) = TestEnvironment.registerProperties(
            registry,
            mapOf(
                "shadow.production.oracle.url" to UNREACHABLE,
                "shadow.production.oracle.replica-url" to TestEnvironment.production.jdbcUrl,
                "shadow.production.oracle.connection-timeout" to "2s",
            ),
        )
    }

    @Test
    fun `when a read replica is configured Production queries go to it and not to the primary`() {
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("t", seedTable())))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
    }
}

/** The replica is unreachable and only the primary answers: nothing may fall back to the primary. */
class BrokenReplicaTest : ShadowTestSupport() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) = TestEnvironment.registerProperties(
            registry,
            mapOf(
                "shadow.production.oracle.replica-url" to UNREACHABLE,
                "shadow.production.oracle.connection-timeout" to "2s",
            ),
        )
    }

    @Test
    fun `Production queries never fall back to the primary when the replica cannot be reached`() {
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("t", seedTable())))
        assertThat(done["verdict"]!!.asText()).isEqualTo("ERROR")
    }
}
