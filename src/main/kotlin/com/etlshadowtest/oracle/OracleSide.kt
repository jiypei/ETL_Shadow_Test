package com.etlshadowtest.oracle

import com.etlshadowtest.api.Location
import com.etlshadowtest.config.OracleProperties
import com.etlshadowtest.target.AggregateSpec
import com.etlshadowtest.target.AggregateValues
import com.etlshadowtest.target.BoundScope
import com.etlshadowtest.target.ColumnCategory
import com.etlshadowtest.target.ColumnMeta
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.PreparedStatement

/** Oracle access for one Environment. */
class OracleSide(val environment: String, props: OracleProperties) : AutoCloseable {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            poolName = "oracle-$environment"
            jdbcUrl = props.url
            username = props.username
            password = props.password
            maximumPoolSize = props.maxConnections
            minimumIdle = 0
            initializationFailTimeout = -1
        },
    )

    /** The table's columns from Oracle's metadata, or null when the table does not exist (or is not visible). */
    fun columns(location: Location): List<ColumnMeta>? = pool.connection.use { c ->
        c.prepareStatement(
            "SELECT COLUMN_NAME, DATA_TYPE FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID",
        ).use { ps ->
            ps.setString(1, location.schema)
            ps.setString(2, location.table)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(ColumnMeta(rs.getString(1), rs.getString(2), categoryOf(rs.getString(2)))) }
            }
        }
    }.ifEmpty { null }

    /** The Aggregate Check, computed inside Oracle in one pass over the Comparison Scope. */
    fun aggregate(location: Location, spec: AggregateSpec, scope: BoundScope?): AggregateValues = pool.connection.use { c ->
        val selects = buildList {
            add("COUNT(*)")
            spec.sumColumns.forEach { add("SUM(${quote(it.name)})") }
            spec.nullColumns.forEach { add("COUNT(${quote(it.name)})") }
            if (spec.fingerprintColumns.isNotEmpty()) add(OracleSql.checksum(spec.fingerprintColumns))
        }
        val sql = "SELECT ${selects.joinToString(", ")} FROM ${qualified(location)}${scopeClause(scope)}"
        c.prepareStatement(sql).use { ps ->
            bindScope(ps, scope)
            ps.executeQuery().use { rs ->
                rs.next()
                var i = 1
                val rowCount = rs.getBigDecimal(i++).longValueExact()
                val sums = spec.sumColumns.associate { it.name to rs.getBigDecimal(i++) }
                val counts = spec.nullColumns.associate { it.name to rs.getBigDecimal(i++).longValueExact() }
                val checksum = if (spec.fingerprintColumns.isNotEmpty()) rs.getBigDecimal(i) else null
                AggregateValues(rowCount, sums, counts, checksum)
            }
        }
    }

    private fun scopeClause(scope: BoundScope?) =
        if (scope == null) "" else " WHERE ${quote(scope.column)} >= ? AND ${quote(scope.column)} < ?"

    private fun bindScope(ps: PreparedStatement, scope: BoundScope?) {
        if (scope != null) {
            ps.setObject(1, scope.from)
            ps.setObject(2, scope.to)
        }
    }

    override fun close() = pool.close()

    companion object {
        fun quote(identifier: String): String {
            require(identifier.isNotEmpty() && '"' !in identifier && '\u0000' !in identifier) { "Invalid identifier: $identifier" }
            return "\"$identifier\""
        }

        fun qualified(location: Location) = "${quote(location.schema!!)}.${quote(location.table!!)}"

        fun categoryOf(dataType: String): ColumnCategory = when {
            dataType == "NUMBER" || dataType == "FLOAT" || dataType == "BINARY_FLOAT" || dataType == "BINARY_DOUBLE" -> ColumnCategory.NUMERIC
            dataType in setOf("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR") -> ColumnCategory.TEXT
            dataType == "DATE" -> ColumnCategory.DATE
            dataType.startsWith("TIMESTAMP") && "TIME ZONE" in dataType -> ColumnCategory.TIMESTAMP_TZ
            dataType.startsWith("TIMESTAMP") -> ColumnCategory.TIMESTAMP
            else -> ColumnCategory.OTHER
        }
    }
}
