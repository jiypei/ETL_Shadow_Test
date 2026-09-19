package com.etlshadowtest.compare

import com.etlshadowtest.run.CheckResult
import com.etlshadowtest.target.AggregateSpec
import com.etlshadowtest.target.AggregateValues
import java.math.BigDecimal

/** Lines up the Aggregate Check values from both Environments, one check at a time. */
object AggregateComparison {
    fun compare(spec: AggregateSpec, staging: AggregateValues, production: AggregateValues): List<CheckResult> = buildList {
        add(check("row_count", null, staging.rowCount.toBigDecimal(), production.rowCount.toBigDecimal()))
        val rows = maxOf(staging.rowCount, production.rowCount).toBigDecimal()
        for (column in spec.sumColumns) {
            val tolerance = spec.tolerances[column.name]
            // Every row within the tolerance means the sums can differ by at most tolerance times the row count.
            add(check("sum", column.name, staging.sums[column.name], production.sums[column.name], tolerance?.multiply(rows), tolerance))
        }
        for (column in spec.toleranceColumns) {
            val tolerance = spec.tolerances.getValue(column.name)
            add(check("min", column.name, staging.mins[column.name], production.mins[column.name], tolerance, tolerance))
            add(check("max", column.name, staging.maxs[column.name], production.maxs[column.name], tolerance, tolerance))
        }
        for (column in spec.nullColumns) {
            val s = staging.rowCount - staging.nonNullCounts.getValue(column.name)
            val p = production.rowCount - production.nonNullCounts.getValue(column.name)
            add(check("null_count", column.name, s.toBigDecimal(), p.toBigDecimal()))
        }
        if (spec.fingerprintColumns.isNotEmpty()) add(check("checksum", null, staging.checksum, production.checksum))
        if (spec.keyColumns.isNotEmpty()) {
            val s = staging.duplicateKeyRows!!.toBigDecimal()
            val p = production.duplicateKeyRows!!.toBigDecimal()
            // A key must identify every row on both sides, so this agrees only when neither side has duplicates.
            add(CheckResult("duplicate_key_rows", null, s, p, agrees = s.signum() == 0 && p.signum() == 0))
        }
    }

    /** [allowedDifference] is null for an exact check. */
    private fun check(
        name: String,
        column: String?,
        staging: BigDecimal?,
        production: BigDecimal?,
        allowedDifference: BigDecimal? = null,
        tolerance: BigDecimal? = null,
    ) = CheckResult(
        name, column, staging, production,
        agrees = if (allowedDifference == null) equalDecimals(staging, production) else withinTolerance(staging, production, allowedDifference),
        method = if (allowedDifference == null) "exact" else "tolerance",
        tolerance = tolerance,
    )

    private fun withinTolerance(a: BigDecimal?, b: BigDecimal?, allowed: BigDecimal) =
        if (a == null || b == null) a == b else a.subtract(b).abs() <= allowed

    /** Exact decimal equality; NULL equals NULL, and NULL equals nothing else. */
    fun equalDecimals(a: BigDecimal?, b: BigDecimal?) = if (a == null || b == null) a == b else a.compareTo(b) == 0
}
