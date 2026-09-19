package com.etlshadowtest.target

import java.math.BigDecimal

/** What an Aggregate Check computes over a Target's Comparison Scope. */
data class AggregateSpec(
    /** Columns summed (numeric, not ignored). */
    val sumColumns: List<ColumnMeta>,
    /** Columns whose non-null values are counted, giving null rates (all non-ignored columns). */
    val nullColumns: List<ColumnMeta>,
    /** Columns that make up the Row Fingerprint and the checksum (non-ignored, non-tolerance). */
    val fingerprintColumns: List<ColumnMeta>,
)

data class AggregateValues(
    val rowCount: Long,
    val sums: Map<String, BigDecimal?>,
    val nonNullCounts: Map<String, Long>,
    /** Order-independent checksum over the Row Fingerprints; null when there are no rows or no fingerprint columns. */
    val checksum: BigDecimal?,
)
