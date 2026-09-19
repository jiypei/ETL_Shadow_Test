package com.etlshadowtest.oracle

import com.etlshadowtest.target.ColumnCategory
import com.etlshadowtest.target.ColumnMeta

/**
 * SQL text built from validated column metadata only. Identifiers are always quoted; values are always bound.
 * The normalization here defines what "equal" means for the Aggregate Check checksum and the Row Fingerprint,
 * so both use the same expressions (ADR 0001).
 */
object OracleSql {
    private const val NLS = "'NLS_NUMERIC_CHARACTERS=''.,'''"
    private const val TIMESTAMP_FORMAT = "'YYYY-MM-DD\"T\"HH24:MI:SS.FF9'"
    private const val NULL_PIECE = "'N00000000000000000000000000000000'"

    fun canonical(column: ColumnMeta): String {
        val q = OracleSide.quote(column.name)
        return when (column.category) {
            ColumnCategory.NUMERIC -> "TO_CHAR($q, 'TM9', $NLS)"
            ColumnCategory.TEXT -> q
            ColumnCategory.DATE, ColumnCategory.TIMESTAMP -> "TO_CHAR(CAST($q AS TIMESTAMP), $TIMESTAMP_FORMAT)"
            ColumnCategory.TIMESTAMP_TZ -> "TO_CHAR(SYS_EXTRACT_UTC($q), $TIMESTAMP_FORMAT)"
            ColumnCategory.OTHER -> throw IllegalArgumentException("Column '${column.name}' has type ${column.dataType}, which cannot be compared")
        }
    }

    /** One fixed-width piece per column: NULL is distinct from every value, so NULL equals NULL and nothing else. */
    private fun piece(column: ColumnMeta): String =
        "CASE WHEN ${OracleSide.quote(column.name)} IS NULL THEN $NULL_PIECE " +
            "ELSE 'V' || RAWTOHEX(STANDARD_HASH(${canonical(column)}, 'MD5')) END"

    /** SQL expression giving the Row Fingerprint (64 hex characters) of the given columns. */
    fun rowFingerprint(columns: List<ColumnMeta>): String {
        val chunks = columns.map(::piece).chunked(100).map { "RAWTOHEX(STANDARD_HASH(${it.joinToString(" || ")}, 'MD5'))" }
        return "RAWTOHEX(STANDARD_HASH(${chunks.joinToString(" || ").ifEmpty { "'-'" }}, 'SHA256'))"
    }

    /** Order-independent checksum: the sum of the first 60 bits of each Row Fingerprint. */
    fun checksum(columns: List<ColumnMeta>): String =
        "SUM(TO_NUMBER(SUBSTR(${rowFingerprint(columns)}, 1, 15), 'XXXXXXXXXXXXXXX'))"
}
