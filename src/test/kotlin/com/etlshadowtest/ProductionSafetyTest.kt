package com.etlshadowtest

import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.ParquetFixtures
import com.etlshadowtest.support.ShadowTestBase
import com.etlshadowtest.support.TempDirWatcher
import com.etlshadowtest.support.TestEnvironment
import com.etlshadowtest.support.TestEnvironment.production
import com.etlshadowtest.support.TestEnvironment.staging
import com.etlshadowtest.support.oracleTarget
import com.etlshadowtest.support.parquetTarget
import com.etlshadowtest.support.shadowRequest
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.SQLException

class ProductionSafetyTest : ShadowTestBase() {
    private fun table(): String {
        val table = uniqueName("SAFE")
        for (db in listOf(staging, production)) { db.createTable(table, "ID NUMBER, NAME VARCHAR2(20)"); db.insert(table, listOf("ID", "NAME"), listOf(1, "a")) }
        return table
    }

    @Test
    fun `in the test environment the Production account is read-only and every Shadow Test still works`() {
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("t", table())))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
        val differing = table().also { production.update("UPDATE $it SET NAME = 'changed'") }
        val failing = api.run(shadowRequest(uniqueName("p"), oracleTarget("t", differing)))
        assertThat(failing["verdict"]!!.asText()).isEqualTo("FAIL")
        assertThat(failing["targets"]!![0]["rowDiff"]["differentRows"].asLong()).isEqualTo(1)
    }

    @Test
    fun `any write attempted against Production with the service's account fails`() {
        val t = table()
        val target = "${TestEnvironment.PRODUCTION_OWNER}.$t"
        production.readerConnection().use { c ->
            c.createStatement().use { s ->
                for (sql in listOf(
                    "INSERT INTO $target (ID, NAME) VALUES (99, 'x')",
                    "UPDATE $target SET NAME = 'x'",
                    "DELETE FROM $target",
                    "TRUNCATE TABLE $target",
                    "DROP TABLE $target",
                    "CREATE TABLE ${uniqueName("MINE")} (ID NUMBER)",
                    "ALTER TABLE $target ADD (EXTRA NUMBER)",
                )) {
                    assertThatThrownBy { s.execute(sql) }.describedAs(sql).isInstanceOf(SQLException::class.java)
                }
            }
        }
        // Nothing changed, and the comparison still passes.
        production.ownerConnection().use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $t").use { rs -> rs.next(); assertThat(rs.getInt(1)).isEqualTo(1) } }
        }
    }

    @Test
    fun `no Test Run needs a write to Production, so the Test Run is unaffected by the account having none`() {
        val path = uniqueName("ro").lowercase()
        ParquetFixtures.staging(path, "ID BIGINT", listOf(listOf(1L)))
        ParquetFixtures.production(path, "ID BIGINT", listOf(listOf(2L)))
        val done = api.run(shadowRequest(uniqueName("p"), oracleTarget("o", table()), parquetTarget("p", path)))
        assertThat(done["targets"]!!.associate { it["name"].asText() to it["verdict"].asText() }).containsEntry("o", "PASS").containsEntry("p", "FAIL")
    }

    @Test
    fun `temporary DuckDB files are removed when a Test Run fails`() {
        val path = uniqueName("broken").lowercase()
        ParquetFixtures.staging(path, "ID BIGINT", listOf(listOf(1L)), "part-0.parquet")
        MinioFixtures.writeText(TestEnvironment.STAGING_BUCKET, "$path/part-1.parquet", "this is not a parquet file")
        ParquetFixtures.production(path, "ID BIGINT", listOf(listOf(1L)))

        val watcher = TempDirWatcher()
        val done = watcher.use { api.run(shadowRequest(uniqueName("p"), parquetTarget("broken", path))).body!! }
        assertThat(done["verdict"].asText()).isEqualTo("ERROR")
        assertThat(watcher.sawWorkspace).describedAs("the Test Run had created its DuckDB working space before failing").isTrue()
        assertThat(watcher.leftovers()).noneMatch { it.fileName.toString() == done["testRunId"].asText() }
    }
}
