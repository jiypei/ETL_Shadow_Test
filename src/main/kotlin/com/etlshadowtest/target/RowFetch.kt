package com.etlshadowtest.target

import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Fetches full rows for a sample of keys, for any database: keys are matched in batches, and each row comes back under
 * the canonical text of its key so the caller can line it up with the Row Diff's keys. Only the dialect's expressions and
 * how a query is run differ (see [SqlDialect]).
 */
class RowFetch(private val dialect: SqlDialect) {
    /**
     * [execute] runs one query: it binds the parameters with `bind` and hands the result to `read`, using the adapter's
     * own connection handling, limits and guards.
     */
    fun fetch(
        from: String,
        columns: List<ColumnMeta>,
        keys: List<ColumnMeta>,
        keyTuples: List<List<String?>>,
        scope: BoundScope?,
        execute: (sql: String, bind: (PreparedStatement) -> Unit, read: (ResultSet) -> Unit) -> Unit,
    ): Map<List<String?>, Map<String, Any?>> {
        val result = LinkedHashMap<List<String?>, Map<String, Any?>>()
        for (batch in keyTuples.chunked(KEYS_PER_QUERY)) {
            val keyMatch = batch.joinToString(" OR ") { "(" + keys.joinToString(" AND ") { key -> "${dialect.quote(key.name)} = ?" } + ")" }
            val selects = keys.map { dialect.canonical(it) } + columns.map { dialect.valueExpression(it) }
            val sql = "SELECT ${selects.joinToString(", ")} FROM $from WHERE ($keyMatch)${scope.andClause(dialect)}"
            execute(
                sql,
                { ps ->
                    var i = 1
                    for (tuple in batch) tuple.forEachIndexed { k, text -> ps.setObject(i++, dialect.bindable(KeyValues.parse(keys[k].category, text))) }
                    scope.bindTo(ps, i, dialect::bindable)
                },
                { rs ->
                    while (rs.next()) {
                        val key = keys.indices.map { rs.getString(it + 1) }
                        result[key] = columns.mapIndexed { n, column -> column.name to dialect.readValue(rs, keys.size + n + 1, column) }.toMap(LinkedHashMap())
                    }
                },
            )
        }
        return result
    }

    private companion object {
        const val KEYS_PER_QUERY = 100
    }
}
