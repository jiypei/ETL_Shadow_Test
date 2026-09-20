package com.etlshadowtest.oracle

import com.etlshadowtest.target.ColumnCategory
import com.etlshadowtest.target.ColumnMeta
import com.etlshadowtest.target.SqlDialect
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * SQL text built from validated column metadata only. Identifiers are always quoted; values are always bound.
 * The normalization here defines what "equal" means for the Aggregate Check checksum and the Row Fingerprint,
 * so both use the same expressions (ADR 0001).
 */
object OracleSql : SqlDialect {
    private const val NLS = "'NLS_NUMERIC_CHARACTERS=''.,'''"
    private const val TIMESTAMP_FORMAT = "'YYYY-MM-DD\"T\"HH24:MI:SS.FF9'"
    private const val NULL_PIECE = "'N00000000000000000000000000000000'"

    private val IEEE_TYPES = setOf("BINARY_FLOAT", "BINARY_DOUBLE")

    /** Maps an Oracle column type to what it means for comparison. Done once, when the Target's metadata is read. */
    fun column(name: String, dataType: String) = ColumnMeta(name, dataType, categoryOf(dataType), dataType in IEEE_TYPES)

    private fun categoryOf(dataType: String): ColumnCategory = when {
        dataType == "NUMBER" || dataType == "FLOAT" || dataType == "BINARY_FLOAT" || dataType == "BINARY_DOUBLE" -> ColumnCategory.NUMERIC
        dataType in setOf("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR") -> ColumnCategory.TEXT
        dataType == "DATE" -> ColumnCategory.DATE
        dataType.startsWith("TIMESTAMP") && "TIME ZONE" in dataType -> ColumnCategory.TIMESTAMP_TZ
        dataType.startsWith("TIMESTAMP") -> ColumnCategory.TIMESTAMP
        else -> ColumnCategory.OTHER
    }

    override fun quote(identifier: String) = OracleSide.quote(identifier)

    override fun valueExpression(column: ColumnMeta) = quote(column.name)

    override fun bindable(value: Any?) = value

    override fun readValue(rs: ResultSet, index: Int, column: ColumnMeta): Any? = when (column.category) {
        ColumnCategory.NUMERIC -> rs.getBigDecimal(index)
        ColumnCategory.TEXT -> rs.getString(index)
        ColumnCategory.DATE, ColumnCategory.TIMESTAMP -> rs.getTimestamp(index)?.toLocalDateTime()?.toString()
        ColumnCategory.TIMESTAMP_TZ -> rs.getObject(index, OffsetDateTime::class.java)?.toString()
        ColumnCategory.OTHER -> throw IllegalArgumentException("Column '${column.name}' has type ${column.dataType}, which cannot be compared")
    }

    override fun sum(column: ColumnMeta) = "SUM(${quote(column.name)})"

    override fun canonical(column: ColumnMeta): String {
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
    override fun rowFingerprint(columns: List<ColumnMeta>): String {
        val chunks = columns.map(::piece).chunked(100).map { "RAWTOHEX(STANDARD_HASH(${it.joinToString(" || ")}, 'MD5'))" }
        return "RAWTOHEX(STANDARD_HASH(${chunks.joinToString(" || ").ifEmpty { "'-'" }}, 'SHA256'))"
    }

    /** Order-independent checksum: the sum of the first 60 bits of each Row Fingerprint. */
    override fun checksum(columns: List<ColumnMeta>): String =
        "SUM(TO_NUMBER(SUBSTR(${rowFingerprint(columns)}, 1, 15), 'XXXXXXXXXXXXXXX'))"
}
