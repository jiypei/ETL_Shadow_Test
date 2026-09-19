package com.etlshadowtest.compare

import com.etlshadowtest.run.CheckResult
import com.etlshadowtest.target.AggregateSpec
import com.etlshadowtest.target.AggregateValues
import java.math.BigDecimal

/** Lines up the Aggregate Check values from both Environments, one check at a time. */
object AggregateComparison {
    fun compare(spec: AggregateSpec, staging: AggregateValues, production: AggregateValues): List<CheckResult> = buildList {
        add(check("row_count", null, staging.rowCount.toBigDecimal(), production.rowCount.toBigDecimal()))
        for (column in spec.sumColumns) {
            add(check("sum", column.name, staging.sums[column.name], production.sums[column.name]))
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

    private fun check(name: String, column: String?, staging: BigDecimal?, production: BigDecimal?) =
        CheckResult(name, column, staging, production, agrees = equalDecimals(staging, production))

    /** Exact decimal equality; NULL equals NULL, and NULL equals nothing else. */
    fun equalDecimals(a: BigDecimal?, b: BigDecimal?) = if (a == null || b == null) a == b else a.compareTo(b) == 0
}
