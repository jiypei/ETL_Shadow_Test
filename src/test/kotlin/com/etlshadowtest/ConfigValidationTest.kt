package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ParquetFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TestEnvironment
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.parquetTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigValidationTest : ShadowTestBase() {
    private fun table(vararg extraColumns: String): String {
        val table = uniqueName("CFG")
        for (db in listOf(staging, production)) db.createTable(table, (listOf("ID NUMBER", "NAME VARCHAR2(20)", "DAY DATE") + extraColumns).joinToString(", "))
        return table
    }

    private fun rejected(pipeline: String, target: Map<String, Any?>, scope: Map<String, Any?>? = null) =
        api.trigger(shadowRequest(pipeline, target, scope = scope)).also {
            assertThat(it.status).describedAs(it.raw).isEqualTo(400)
            assertNoTestRunCreated(pipeline)
        }

    private fun assertNoTestRunCreated(pipeline: String) =
        assertThat(MinioFixtures.list(TestEnvironment.RESULTS_BUCKET, "results/$pipeline/")).isEmpty()

    @Test
    fun `a table that exists in neither Environment is rejected with an error naming it`() {
        val response = rejected(uniqueName("p"), oracleTarget("orders", "NO_SUCH_TABLE"))
        assertThat(response.raw).contains("orders").contains("Staging or Production")
    }

    @Test
    fun `a table that exists in one Environment only is not a typo and is reported as ERROR by the Test Run`() {
        val table = uniqueName("ONE")
        staging.createTable(table, "ID NUMBER")
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("orders", table)))
        assertThat(done["verdict"]!!.asText()).isEqualTo("ERROR")
    }

    @Test
    fun `a key column that does not exist is rejected with an error naming it`() {
        val response = rejected(uniqueName("p"), oracleTarget("orders", table(), extra = mapOf("keyColumns" to listOf("NOPE"))))
        assertThat(response.raw).contains("orders").contains("key column 'NOPE'")
    }

    @Test
    fun `an ignored column that does not exist is rejected with an error naming it`() {
        val response = rejected(uniqueName("p"), oracleTarget("orders", table(), extra = mapOf("ignoredColumns" to listOf("GHOST"))))
        assertThat(response.raw).contains("ignored column 'GHOST'")
    }

    @Test
    fun `a scope column that does not exist is rejected with an error naming it`() {
        val response = rejected(
            uniqueName("p"),
            oracleTarget("orders", table(), extra = mapOf("scopeColumn" to "MISSING")),
            scope = mapOf("from" to "2026-01-01", "to" to "2026-02-01"),
        )
        assertThat(response.raw).contains("scope column 'MISSING'")
    }

    @Test
    fun `a tolerance column that does not exist is rejected with an error naming it`() {
        val response = rejected(uniqueName("p"), oracleTarget("orders", table(), extra = mapOf("tolerances" to mapOf("PHANTOM" to 0.1))))
        assertThat(response.raw).contains("PHANTOM")
    }

    @Test
    fun `a key column cannot also be ignored`() {
        rejected(uniqueName("p"), oracleTarget("orders", table(), extra = mapOf("ignoredColumns" to listOf("ID"))))
    }

    @Test
    fun `names containing quotes, semicolons, comments or SQL keywords never change what runs`() {
        val t = table()
        val hostile = listOf(
            "ID\" OR \"1\"=\"1",
            "ID; DROP TABLE $t; --",
            "ID -- comment",
            "ID/**/",
            "ID' OR '1'='1",
            "(SELECT 1 FROM DUAL)",
            "ID) FROM DUAL UNION SELECT 1 --",
        )
        for (name in hostile) {
            rejected(uniqueName("p"), oracleTarget("orders", t, extra = mapOf("keyColumns" to listOf(name))))
            rejected(uniqueName("p"), oracleTarget("orders", t, extra = mapOf("ignoredColumns" to listOf(name))))
            rejected(uniqueName("p"), oracleTarget("orders", t, extra = mapOf("scopeColumn" to name)), scope = mapOf("from" to "2026-01-01", "to" to "2026-02-01"))
            rejected(uniqueName("p"), oracleTarget("orders", t, extra = mapOf("tolerances" to mapOf(name to 0.1))))
        }
        for (name in hostile) {
            rejected(uniqueName("p"), oracleTarget("orders", name))
            // Missing on one side only is a possible missing deployment, so it runs and reports ERROR; the name is only ever bound.
            val oneSide = api.run(shadowRequest(uniqueName("p"), oracleTarget("orders", t) + mapOf("staging" to mapOf("schema" to name, "table" to t))))
            assertThat(oneSide["verdict"]!!.asText()).isEqualTo("ERROR")
            assertThat(oneSide["targets"]!![0]["reason"].asText()).contains("missing in Staging")
        }
        // The table is untouched and still comparable.
        assertThat(api.run(shadowRequest(uniqueName("p"), oracleTarget("orders", t)))["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `hostile Target and Pipeline names are rejected`() {
        val t = table()
        for (name in listOf("../escape", "a b", "x/../../y", "name;drop", "")) {
            rejected(uniqueName("p"), oracleTarget(name, t))
            assertThat(api.trigger(shadowRequest(name, oracleTarget("orders", t))).status).isEqualTo(400)
        }
    }

    @Test
    fun `hostile Parquet paths are rejected`() {
        for (path in listOf("../other-bucket", "/absolute", "x') ; SELECT 1 --", "a/../b", "x' OR '1'='1", "glob/*")) {
            rejected(uniqueName("p"), parquetTarget("events", path))
        }
    }

    @Test
    fun `a value where a name is expected is rejected`() {
        val t = table()
        val subquery = mapOf("keyColumns" to listOf(mapOf("sql" to "SELECT 1")))
        assertThat(api.trigger(shadowRequest(uniqueName("p"), oracleTarget("orders", t, extra = subquery))).status).isEqualTo(400)
        val objectAsTable = oracleTarget("orders", t) + mapOf("staging" to mapOf("schema" to TestEnvironment.STAGING_OWNER, "table" to mapOf("select" to 1)))
        assertThat(api.trigger(shadowRequest(uniqueName("p"), objectAsTable)).status).isEqualTo(400)
        assertThat(api.trigger(shadowRequest(uniqueName("p"), oracleTarget("orders", t, extra = mapOf("where" to "1=1")))).status).isEqualTo(400)
    }

    @Test
    fun `scope values are bound as values and can never change the query`() {
        val t = uniqueName("SC")
        for (db in listOf(staging, production)) db.createTable(t, "ID NUMBER, BATCH VARCHAR2(30)")
        staging.insert(t, listOf("ID", "BATCH"), listOf(1, "a"))
        production.insert(t, listOf("ID", "BATCH"), listOf(1, "a"), listOf(2, "b"))
        val injected = mapOf("from" to "x' OR '1'='1", "to" to "z' OR '1'='1")
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("orders", t, extra = mapOf("scopeColumn" to "BATCH")), scope = injected))
        // Neither literal matches any row, so both sides are empty and agree; the OR clause never ran.
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
        val badDate = api.trigger(shadowRequest(uniqueName("p"), oracleTarget("orders", table(), extra = mapOf("scopeColumn" to "DAY")), scope = injected))
        assertThat(badDate.status).isEqualTo(400)
    }

    @Test
    fun `unusual but valid names are safely quoted and unaffected`() {
        val table = "\"Odd ${uniqueName("Tbl")}\""
        for (db in listOf(staging, production)) db.createTable(table, "\"ID\" NUMBER, \"select\" VARCHAR2(10), \"Mixed Case\" NUMBER")
        for (db in listOf(staging, production)) db.insert(table, listOf("ID", "select", "Mixed Case"), listOf(1, "x", 5), listOf(2, "y", 6))
        val plain = table.trim('"')
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("odd", plain, extra = mapOf("ignoredColumns" to listOf("select")))))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")

        val keyed = api.run(shadowRequest(uniqueName("p"), oracleTarget("odd", plain, extra = mapOf("keyColumns" to listOf("Mixed Case")))))
        assertThat(keyed["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `valid Parquet requests are unaffected`() {
        val path = uniqueName("valid").lowercase() + "/year=2026"
        ParquetFixtures.staging(path, "ID BIGINT", listOf(listOf(1L)))
        ParquetFixtures.production(path, "ID BIGINT", listOf(listOf(1L)))
        assertThat(api.run(shadowRequest(uniqueName("p"), parquetTarget("events", path)))["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `a callback address is rejected when no callback hosts are configured`() {
        val response = api.trigger(shadowRequest(uniqueName("p"), oracleTarget("orders", table()), callbackUrl = "http://127.0.0.1:9/hook"))
        assertThat(response.status).isEqualTo(400)
        assertThat(response.raw).contains("callbackUrl").contains("not enabled")
    }

    @Test
    fun `a key column of a type that cannot be matched is rejected up front`() {
        val t = uniqueName("TZ")
        for (db in listOf(staging, production)) db.createTable(t, "ID TIMESTAMP WITH TIME ZONE, NAME VARCHAR2(10)")
        val response = rejected(uniqueName("p"), oracleTarget("orders", t))
        assertThat(response.raw).contains("key column 'ID'").contains("cannot be used as a key")
    }

    @Test
    fun `a Test Run ID that is not a UUID is rejected`() {
        assertThat(api.poll(uniqueName("p"), "not-a-uuid").status).isEqualTo(400)
        assertThat(api.poll(uniqueName("p"), "../../x").status).isIn(400, 404)
    }
}
