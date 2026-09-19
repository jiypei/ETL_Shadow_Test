package com.etlshadowtest.oracle

import com.etlshadowtest.api.Location
import com.etlshadowtest.config.OracleProperties
import com.etlshadowtest.duckdb.Workspace
import com.etlshadowtest.target.AggregateSpec
import com.etlshadowtest.target.AggregateValues
import com.etlshadowtest.target.BoundScope
import com.etlshadowtest.target.ColumnCategory
import com.etlshadowtest.target.ColumnMeta
import com.etlshadowtest.target.KeyValues
import com.etlshadowtest.target.TargetSide
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.duckdb.DuckDBConnection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime

/** Oracle access for one Environment. */
class OracleSide(override val environment: String, props: OracleProperties) : TargetSide, AutoCloseable {
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
    override fun columns(location: Location): List<ColumnMeta>? = pool.connection.use { c ->
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
    override fun aggregate(location: Location, spec: AggregateSpec, scope: BoundScope?): AggregateValues = pool.connection.use { c ->
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

    override fun loadKeyFingerprints(
        location: Location,
        keys: List<ColumnMeta>,
        fingerprint: List<ColumnMeta>,
        scope: BoundScope?,
        workspace: Workspace,
        table: String,
    ) {
        val keyColumns = keys.indices.joinToString(", ") { "k$it VARCHAR" }
        workspace.execute("CREATE TABLE $table ($keyColumns, fp VARCHAR)")
        val selects = keys.map { OracleSql.canonical(it) } + OracleSql.rowFingerprint(fingerprint)
        val sql = "SELECT ${selects.joinToString(", ")} FROM ${qualified(location)}${scopeClause(scope)}"
        val duck = workspace.connection as DuckDBConnection
        duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, table).use { appender ->
            pool.connection.use { c ->
                c.prepareStatement(sql).use { ps ->
                    ps.fetchSize = FETCH_SIZE
                    bindScope(ps, scope)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            appender.beginRow()
                            for (i in 1..selects.size) appender.append(rs.getString(i))
                            appender.endRow()
                        }
                    }
                }
            }
        }
    }

    override fun fetchRows(
        location: Location,
        columns: List<ColumnMeta>,
        keys: List<ColumnMeta>,
        keyTuples: List<List<String?>>,
        scope: BoundScope?,
    ): Map<List<String?>, Map<String, Any?>> {
        val result = LinkedHashMap<List<String?>, Map<String, Any?>>()
        for (batch in keyTuples.chunked(FETCH_KEYS_PER_QUERY)) {
            val keyMatch = batch.joinToString(" OR ") { "(" + keys.joinToString(" AND ") { k -> "${quote(k.name)} = ?" } + ")" }
            val selects = keys.map { OracleSql.canonical(it) } + columns.map { quote(it.name) }
            val sql = "SELECT ${selects.joinToString(", ")} FROM ${qualified(location)} WHERE ($keyMatch)" +
                if (scope == null) "" else " AND ${quote(scope.column)} >= ? AND ${quote(scope.column)} < ?"
            pool.connection.use { c ->
                c.prepareStatement(sql).use { ps ->
                    var i = 1
                    for (tuple in batch) tuple.forEachIndexed { k, text -> ps.setObject(i++, KeyValues.parse(keys[k].category, text)) }
                    if (scope != null) { ps.setObject(i++, scope.from); ps.setObject(i, scope.to) }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val key = keys.indices.map { rs.getString(it + 1) }
                            val values = columns.mapIndexed { n, col -> col.name to readValue(rs, keys.size + n + 1, col) }.toMap(LinkedHashMap())
                            result[key] = values
                        }
                    }
                }
            }
        }
        return result
    }

    private fun readValue(rs: ResultSet, index: Int, column: ColumnMeta): Any? = when (column.category) {
        ColumnCategory.NUMERIC -> rs.getBigDecimal(index)
        ColumnCategory.TEXT -> rs.getString(index)
        ColumnCategory.DATE, ColumnCategory.TIMESTAMP -> rs.getTimestamp(index)?.toLocalDateTime()?.toString()
        ColumnCategory.TIMESTAMP_TZ -> rs.getObject(index, OffsetDateTime::class.java)?.toString()
        ColumnCategory.OTHER -> throw IllegalArgumentException("Column '${column.name}' has type ${column.dataType}, which cannot be compared")
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
        private const val FETCH_SIZE = 10_000
        private const val FETCH_KEYS_PER_QUERY = 100

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
