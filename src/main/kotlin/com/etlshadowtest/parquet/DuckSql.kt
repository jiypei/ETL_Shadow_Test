package com.etlshadowtest.parquet

import com.etlshadowtest.duckdb.Workspace
import com.etlshadowtest.target.ColumnCategory
import com.etlshadowtest.target.ColumnMeta

/**
 * SQL text for DuckDB over Parquet, built from validated column metadata only.
 * The normalization defines what "equal" means for Parquet Targets, mirroring the Oracle rules:
 * exact decimals, NULL equals NULL and nothing else, strings untrimmed, dates and timestamps compared as timestamps.
 */
object DuckSql {
    private const val TIMESTAMP_FORMAT = "'%Y-%m-%dT%H:%M:%S.%n'"
    private const val NULL_PIECE = "'N00000000000000000000000000000000'"
    private val PATH = Regex("[A-Za-z0-9._=/-]+")

    fun quote(identifier: String): String {
        require(identifier.isNotEmpty() && '\u0000' !in identifier) { "Invalid identifier: $identifier" }
        return "\"" + identifier.replace("\"", "\"\"") + "\""
    }

    fun isValidPath(path: String) = PATH.matches(path) && !path.startsWith("/") && ".." !in path.split("/")

    /** All Parquet files under [path] in the Environment's bucket, however deeply nested. */
    fun dataset(bucket: String, path: String): String {
        require(isValidPath(path)) { "Invalid Parquet path: $path" }
        return "read_parquet(${Workspace.literal("s3://$bucket/${path.trimEnd('/')}/**/*.parquet")})"
    }

    fun canonical(column: ColumnMeta): String {
        val q = quote(column.name)
        return when (column.category) {
            ColumnCategory.NUMERIC -> "CAST($q AS VARCHAR)"
            ColumnCategory.TEXT -> q
            ColumnCategory.DATE, ColumnCategory.TIMESTAMP -> "strftime(CAST($q AS TIMESTAMP_NS), $TIMESTAMP_FORMAT)"
            ColumnCategory.TIMESTAMP_TZ -> "strftime(CAST(timezone('UTC', $q) AS TIMESTAMP_NS), $TIMESTAMP_FORMAT)"
            ColumnCategory.OTHER -> throw IllegalArgumentException("Column '${column.name}' has type ${column.dataType}, which cannot be compared")
        }
    }

    private fun piece(column: ColumnMeta) =
        "CASE WHEN ${quote(column.name)} IS NULL THEN $NULL_PIECE ELSE 'V' || md5(${canonical(column)}) END"

    /** SQL expression giving the Row Fingerprint (32 hex characters) of the given columns. */
    fun rowFingerprint(columns: List<ColumnMeta>): String =
        "md5(${columns.joinToString(" || ") { piece(it) }.ifEmpty { "'-'" }})"

    fun checksum(columns: List<ColumnMeta>) = "CAST(sum(CAST(hash(${rowFingerprint(columns)}) AS HUGEINT)) AS DECIMAL(38,0))"

    fun categoryOf(type: String): ColumnCategory = when {
        type in NUMERIC_TYPES || type.startsWith("DECIMAL") -> ColumnCategory.NUMERIC
        type == "VARCHAR" -> ColumnCategory.TEXT
        type == "DATE" -> ColumnCategory.DATE
        type == "TIMESTAMP WITH TIME ZONE" -> ColumnCategory.TIMESTAMP_TZ
        type.startsWith("TIMESTAMP") -> ColumnCategory.TIMESTAMP
        else -> ColumnCategory.OTHER
    }

    private val NUMERIC_TYPES = setOf(
        "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT", "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT", "FLOAT", "DOUBLE",
    )
}
