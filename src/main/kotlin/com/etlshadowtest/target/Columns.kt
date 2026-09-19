package com.etlshadowtest.target

import com.etlshadowtest.api.ScopeRange
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

enum class ColumnCategory { NUMERIC, TEXT, DATE, TIMESTAMP, TIMESTAMP_TZ, OTHER }

/** A column as the Target's real metadata reports it. */
data class ColumnMeta(val name: String, val dataType: String, val category: ColumnCategory)

/** The Comparison Scope with its range already converted to the scope column's type. */
data class BoundScope(val column: String, val from: Any, val to: Any)

object ScopeValues {
    /** Converts a scope bound to the value type of the scope column, or fails with a message naming the problem. */
    fun bind(column: ColumnMeta, range: ScopeRange): BoundScope =
        BoundScope(column.name, convert(column, range.from, "from"), convert(column, range.to, "to"))

    private fun convert(column: ColumnMeta, text: String, bound: String): Any = try {
        when (column.category) {
            ColumnCategory.NUMERIC -> BigDecimal(text)
            ColumnCategory.TEXT -> text
            ColumnCategory.DATE, ColumnCategory.TIMESTAMP, ColumnCategory.TIMESTAMP_TZ ->
                Timestamp.valueOf(if ('T' in text) LocalDateTime.parse(text) else LocalDate.parse(text).atStartOfDay())
            ColumnCategory.OTHER -> throw IllegalArgumentException("scope column '${column.name}' has type ${column.dataType}, which cannot be used as a scope column")
        }
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("scope '$bound' value '$text' is not a valid ${column.dataType} for scope column '${column.name}'")
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("scope '$bound' value '$text' is not a valid ${column.dataType} for scope column '${column.name}'")
    }
}
