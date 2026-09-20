package com.etlshadowtest.target

import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The Aggregate Check as one query for any database. The select-list and the reading of its result are built from the
 * same [AggregateSpec] here, side by side, so they cannot drift apart; a dialect only supplies the few expressions
 * that differ (see [SqlDialect]).
 */
class AggregateQuery(private val spec: AggregateSpec, private val dialect: SqlDialect) {
    private val selects: List<String> = buildList {
        add("COUNT(*)")
        spec.sumColumns.forEach { add(dialect.sum(it)) }
        spec.nullColumns.forEach { add("COUNT(${dialect.quote(it.name)})") }
        spec.toleranceColumns.forEach { add("MIN(${dialect.quote(it.name)})"); add("MAX(${dialect.quote(it.name)})") }
        if (spec.fingerprintColumns.isNotEmpty()) add(dialect.checksum(spec.fingerprintColumns))
        if (spec.keyColumns.isNotEmpty()) add("COUNT(DISTINCT ${dialect.rowFingerprint(spec.keyColumns)})")
    }

    /** The query over [from] (a quoted table or a dataset expression), limited to [scope] if there is one. */
    fun sql(from: String, scope: BoundScope?) = "SELECT ${selects.joinToString(", ")} FROM $from${scope.whereClause(dialect)}"

    /** Reads the one result row, in the order [sql] selected it. */
    fun read(rs: ResultSet): AggregateValues {
        rs.next()
        var i = 1
        val rowCount = rs.getLong(i++)
        val sums = spec.sumColumns.associate { it.name to rs.getBigDecimal(i++) }
        val counts = spec.nullColumns.associate { it.name to rs.getLong(i++) }
        val mins = LinkedHashMap<String, BigDecimal?>()
        val maxs = LinkedHashMap<String, BigDecimal?>()
        for (column in spec.toleranceColumns) { mins[column.name] = rs.getBigDecimal(i++); maxs[column.name] = rs.getBigDecimal(i++) }
        val checksum = if (spec.fingerprintColumns.isNotEmpty()) rs.getBigDecimal(i++) else null
        val duplicates = if (spec.keyColumns.isNotEmpty()) rowCount - rs.getLong(i) else null
        return AggregateValues(rowCount, sums, counts, mins, maxs, checksum, duplicates)
    }
}

/** ` WHERE col >= ? AND col < ?` for the Comparison Scope, or nothing when the Target is compared whole. */
fun BoundScope?.whereClause(dialect: SqlDialect): String =
    if (this == null) "" else " WHERE ${dialect.quote(column)} >= ? AND ${dialect.quote(column)} < ?"

/** ` AND col >= ? AND col < ?`, for a query that already has a WHERE clause. */
fun BoundScope?.andClause(dialect: SqlDialect): String =
    if (this == null) "" else " AND ${dialect.quote(column)} >= ? AND ${dialect.quote(column)} < ?"

/** Binds the scope's two values from parameter [first] and returns the next free index. [convert] adapts them to a driver. */
fun BoundScope?.bindTo(ps: PreparedStatement, first: Int, convert: (Any) -> Any? = { it }): Int {
    var i = first
    if (this != null) {
        ps.setObject(i++, convert(from))
        ps.setObject(i++, convert(to))
    }
    return i
}
