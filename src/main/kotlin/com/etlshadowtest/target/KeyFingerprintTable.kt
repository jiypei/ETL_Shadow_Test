package com.etlshadowtest.target

import com.etlshadowtest.duckdb.Workspace

/**
 * The table a Row Diff loads from each side into the Test Run's DuckDB: one row per row in the Comparison Scope, with
 * the key's canonical text in `k0`, `k1`, ... and the Row Fingerprint in `fp`. This is the agreement between the
 * adapters, which fill it, and the Row Diff, which reads it, so the layout is written down here and nowhere else.
 */
class KeyFingerprintTable(val name: String, keyCount: Int) {
    val keyColumns: List<String> = List(keyCount) { "k$it" }

    /** `k0, k1` for use in a select-list, group by or order by. */
    val keyList: String get() = keyColumns.joinToString(", ")

    /** Creates the empty table, for an adapter that streams rows into it. */
    fun create(workspace: Workspace) {
        val definitions = keyColumns.map { "$it VARCHAR" } + "$FINGERPRINT VARCHAR"
        workspace.execute("CREATE TABLE $name (${definitions.joinToString(", ")})")
    }

    /** The query giving this table's rows, in this table's column order, from [from] limited to [scope]. */
    fun select(dialect: SqlDialect, keys: List<ColumnMeta>, fingerprint: List<ColumnMeta>, from: String, scope: BoundScope?): String {
        val selects = keys.mapIndexed { i, key -> "${dialect.canonical(key)} AS ${keyColumns[i]}" } +
            "${dialect.rowFingerprint(fingerprint)} AS $FINGERPRINT"
        return "SELECT ${selects.joinToString(", ")} FROM $from${scope.whereClause(dialect)}"
    }

    companion object {
        const val FINGERPRINT = "fp"
    }
}
