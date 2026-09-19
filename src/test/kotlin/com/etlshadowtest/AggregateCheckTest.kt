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
import java.math.BigDecimal
import java.time.LocalDateTime

class AggregateCheckTest : ShadowTestBase() {
    private val columns = "ID NUMBER, AMOUNT NUMBER(38,20), NAME VARCHAR2(50), NOTE VARCHAR2(50), CREATED DATE, LOAD_TS TIMESTAMP"
    private val names = listOf("ID", "AMOUNT", "NAME", "NOTE", "CREATED", "LOAD_TS")
    private val created = LocalDateTime.of(2026, 1, 1, 10, 30, 0)

    private fun row(
        id: Int,
        amount: String? = "10.5",
        name: String? = "alpha",
        note: String? = null,
        createdAt: LocalDateTime? = created,
        loadTs: LocalDateTime = LocalDateTime.of(2026, 2, 1, 0, 0, 1),
    ) = listOf<Any?>(id, amount?.let(::BigDecimal), name, note, createdAt, loadTs)

    private fun seed(stagingRows: List<List<Any?>>, productionRows: List<List<Any?>>, tableColumns: String = columns): String {
        val table = uniqueName("AGG")
        for (db in listOf(staging, production)) db.createTable(table, tableColumns)
        staging.insert(table, names, *stagingRows.toTypedArray())
        production.insert(table, names, *productionRows.toTypedArray())
        return table
    }

    private fun run(table: String, extra: Map<String, Any?> = emptyMap()) =
        api.run(shadowRequest(uniqueName("p"), oracleTarget("t", table, extra = extra)))

    private fun JsonNode.disagreeing(): List<String> =
        this["targets"][0]["aggregateCheck"]["checks"].filter { !it["agrees"].asBoolean() }
            .map { it["check"].asText() + (it["column"]?.takeIf { c -> !c.isNull }?.let { c -> ":" + c.asText() } ?: "") }

    private fun JsonNode.check(check: String, column: String? = null): JsonNode =
        this["targets"][0]["aggregateCheck"]["checks"].first {
            it["check"].asText() == check && (column == null || it["column"].asText() == column)
        }

    @Test
    fun `identical data gives PASS`() {
        val rows = listOf(row(1), row(2, note = "x"), row(3, amount = null, name = null))
        val done = run(seed(rows, rows))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
        assertThat(done.body!!.disagreeing()).isEmpty()
    }

    @Test
    fun `the report shows the value from each Environment for every check`() {
        val rows = listOf(row(1), row(2, note = "x"))
        val done = run(seed(rows, rows)).body!!
        val checks = done["targets"][0]["aggregateCheck"]["checks"].map { it["check"].asText() to it["column"].asText() }
        assertThat(checks).contains("row_count" to "null", "sum" to "AMOUNT", "sum" to "ID", "null_count" to "NOTE", "checksum" to "null")
        assertThat(done.check("sum", "AMOUNT")["staging"].decimalValue()).isEqualByComparingTo("21.0")
        assertThat(done.check("sum", "AMOUNT")["production"].decimalValue()).isEqualByComparingTo("21.0")
        assertThat(done.check("null_count", "NOTE")["staging"].asLong()).isEqualTo(1)
    }

    @Test
    fun `a difference in row count gives FAIL and shows both values`() {
        val done = run(seed(listOf(row(1), row(2)), listOf(row(1)))).body!!
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.disagreeing()).contains("row_count")
        assertThat(done.check("row_count")["staging"].asLong()).isEqualTo(2)
        assertThat(done.check("row_count")["production"].asLong()).isEqualTo(1)
    }

    @Test
    fun `a difference in a numeric sum gives FAIL`() {
        val done = run(seed(listOf(row(1, amount = "10.5")), listOf(row(1, amount = "11.5")))).body!!
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.disagreeing()).contains("sum:AMOUNT")
        assertThat(done.check("sum", "AMOUNT")["staging"].decimalValue()).isEqualByComparingTo("10.5")
        assertThat(done.check("sum", "AMOUNT")["production"].decimalValue()).isEqualByComparingTo("11.5")
    }

    @Test
    fun `a difference in a column's null rate gives FAIL`() {
        val done = run(seed(listOf(row(1, note = null)), listOf(row(1, note = "x")))).body!!
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.disagreeing()).contains("null_count:NOTE")
        assertThat(done.check("null_count", "NOTE")["staging"].asLong()).isEqualTo(1)
        assertThat(done.check("null_count", "NOTE")["production"].asLong()).isEqualTo(0)
    }

    @Test
    fun `a value that changes only the checksum gives FAIL and names the checksum`() {
        val done = run(seed(listOf(row(1, name = "alpha")), listOf(row(1, name = "alphb")))).body!!
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.disagreeing()).containsExactly("checksum")
    }

    @Test
    fun `the checksum does not depend on row order`() {
        val a = listOf(row(1), row(2, name = "beta"), row(3, name = "gamma"))
        val done = run(seed(a, a.reversed()))
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `a difference confined to an ignored column gives PASS`() {
        val done = run(
            seed(listOf(row(1, loadTs = LocalDateTime.of(2026, 3, 1, 0, 0)), row(2)), listOf(row(1, loadTs = LocalDateTime.of(2026, 3, 2, 0, 0)), row(2))),
            extra = mapOf("ignoredColumns" to listOf("LOAD_TS")),
        )
        assertThat(done["verdict"]!!.asText()).isEqualTo("PASS")
    }

    @Test
    fun `without the ignore declaration the same difference gives FAIL`() {
        val done = run(seed(listOf(row(1, loadTs = LocalDateTime.of(2026, 3, 1, 0, 0))), listOf(row(1, loadTs = LocalDateTime.of(2026, 3, 2, 0, 0)))))
        assertThat(done["verdict"]!!.asText()).isEqualTo("FAIL")
    }

    @Test
    fun `decimal values that differ only beyond float precision are detected`() {
        val done = run(seed(listOf(row(1, amount = "0.12345678901234567891")), listOf(row(1, amount = "0.12345678901234567892")))).body!!
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.disagreeing()).contains("sum:AMOUNT", "checksum")
    }

    @Test
    fun `NULL equals NULL but NULL is not equal to an empty-looking value`() {
        val nulls = run(seed(listOf(row(1, note = null, amount = null)), listOf(row(1, note = null, amount = null))))
        assertThat(nulls["verdict"]!!.asText()).isEqualTo("PASS")

        val nullVsBlank = run(seed(listOf(row(1, note = null)), listOf(row(1, note = " ")))).body!!
        assertThat(nullVsBlank["verdict"].asText()).isEqualTo("FAIL")
        assertThat(nullVsBlank.disagreeing()).contains("null_count:NOTE")

        val nullVsZero = run(seed(listOf(row(1, amount = null)), listOf(row(1, amount = "0")))).body!!
        assertThat(nullVsZero["verdict"].asText()).isEqualTo("FAIL")
    }

    @Test
    fun `strings that differ only by trailing spaces are detected as different`() {
        val done = run(seed(listOf(row(1, name = "alpha")), listOf(row(1, name = "alpha ")))).body!!
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.disagreeing()).containsExactly("checksum")
    }

    @Test
    fun `a date that differs only in its time component is detected as different`() {
        val done = run(
            seed(listOf(row(1, createdAt = LocalDateTime.of(2026, 1, 1, 0, 0, 0))), listOf(row(1, createdAt = LocalDateTime.of(2026, 1, 1, 0, 0, 1)))),
        ).body!!
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done.disagreeing()).containsExactly("checksum")
    }

    @Test
    fun `a timestamp that differs only in fractional seconds is detected as different`() {
        val done = run(
            seed(listOf(row(1, loadTs = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 1_000))), listOf(row(1, loadTs = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 2_000)))),
        ).body!!
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
    }

    @Test
    fun `a column that exists on one side only is a schema difference and gives FAIL`() {
        val table = uniqueName("AGG")
        staging.createTable(table, "ID NUMBER, NAME VARCHAR2(10), EXTRA VARCHAR2(10)")
        production.createTable(table, "ID NUMBER, NAME VARCHAR2(10)")
        val done = run(table).body!!
        assertThat(done["verdict"].asText()).isEqualTo("FAIL")
        assertThat(done["targets"][0]["schemaDifferences"].map { it.asText() }).anyMatch { it.contains("EXTRA") }
    }

    @Test
    fun `a column type that cannot be compared must be declared ignored`() {
        val table = uniqueName("AGG")
        for (db in listOf(staging, production)) db.createTable(table, "ID NUMBER, BODY CLOB")
        val rejected = api.trigger(shadowRequest(uniqueName("p"), oracleTarget("t", table)))
        assertThat(rejected.status).isEqualTo(400)
        assertThat(rejected.raw).contains("BODY").contains("ignoredColumns")

        val accepted = run(table, extra = mapOf("ignoredColumns" to listOf("BODY")))
        assertThat(accepted["verdict"]!!.asText()).isEqualTo("PASS")
    }
}
