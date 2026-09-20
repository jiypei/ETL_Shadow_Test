package com.etlshadowtest.target

import com.etlshadowtest.api.ScopeRange
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * What a column's native type means for comparison, whichever dialect it came from. Each adapter maps its native types to
 * a category once, when it reads the Target's metadata; everything derived from a category lives here.
 */
enum class ColumnCategory(
    val numeric: Boolean = false,
    val temporal: Boolean = false,
    /** False for types that cannot be compared exactly (LOBs, RAW, booleans, intervals): they must be ignored. */
    val comparable: Boolean = true,
    /** Keys are matched by their text and bound back as values, which works for these types only. */
    val usableAsKey: Boolean = true,
) {
    NUMERIC(numeric = true),
    TEXT,
    DATE(temporal = true),
    TIMESTAMP(temporal = true),
    TIMESTAMP_TZ(temporal = true, usableAsKey = false),
    OTHER(comparable = false, usableAsKey = false),
}

/** A column as the Target's real metadata reports it. */
data class ColumnMeta(
    val name: String,
    val dataType: String,
    val category: ColumnCategory,
    /**
     * An IEEE floating-point column (Oracle BINARY_FLOAT and BINARY_DOUBLE, Parquet FLOAT and DOUBLE). Adding such values
     * is not associative, so a sum over identical values can differ with row order; decimals are exact.
     */
    val floatingPoint: Boolean = false,
)

/** The Comparison Scope with its range already converted to the scope column's type. */
data class BoundScope(val column: String, val from: Any, val to: Any)

object ScopeValues {
    /** Converts a scope bound to the value type of the scope column, or fails with a message naming the problem. */
    fun bind(column: ColumnMeta, range: ScopeRange): BoundScope {
        val from = convert(column, range.from, "from")
        val to = convert(column, range.to, "to")
        // The bounds have the same type, so they compare. An empty range would compare nothing and pass.
        @Suppress("UNCHECKED_CAST")
        require((from as Comparable<Any>).compareTo(to) < 0) { "scope 'from' (${range.from}) must be before 'to' (${range.to})" }
        return BoundScope(column.name, from, to)
    }

    private fun convert(column: ColumnMeta, text: String, bound: String): Any {
        require(column.category.comparable) { "scope column '${column.name}' has type ${column.dataType}, which cannot be used as a scope column" }
        return try {
            CategoryValues.bind(column.category, text)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("scope '$bound' value '$text' is not a valid ${column.dataType} for scope column '${column.name}'")
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("scope '$bound' value '$text' is not a valid ${column.dataType} for scope column '${column.name}'")
        }
    }
}

/** The one place that turns text (a scope bound, or a key's canonical text) into a value that can be bound as a parameter. */
object CategoryValues {
    fun bind(category: ColumnCategory, text: String): Any = when {
        category.numeric -> BigDecimal(text)
        category.temporal -> Timestamp.valueOf(if ('T' in text) LocalDateTime.parse(text) else LocalDate.parse(text).atStartOfDay())
        category.comparable -> text
        else -> throw IllegalArgumentException("A $category column has no value that can be bound")
    }
}

object KeyValues {
    /** A key's canonical text as it appears in reports: numbers as numbers, everything else as text. */
    fun display(category: ColumnCategory, text: String?): Any? =
        if (text != null && category.numeric) BigDecimal(text) else text

    /** Turns a key's canonical text (as produced for the Row Fingerprint) back into a value that can be bound as a parameter. */
    fun parse(category: ColumnCategory, text: String?): Any? {
        if (text == null) return null
        require(category.usableAsKey) { "A $category column cannot be used as a key" }
        return CategoryValues.bind(category, text)
    }
}
