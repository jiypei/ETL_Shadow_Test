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
    /** Key columns; when present, the Aggregate Check also counts rows whose key is not unique. */
    val keyColumns: List<ColumnMeta> = emptyList(),
    /** Columns with a numeric tolerance: excluded from the fingerprint, checked by sum, min and max within the tolerance. */
    val toleranceColumns: List<ColumnMeta> = emptyList(),
    val tolerances: Map<String, BigDecimal> = emptyMap(),
)

data class AggregateValues(
    val rowCount: Long,
    val sums: Map<String, BigDecimal?>,
    val nonNullCounts: Map<String, Long>,
    val mins: Map<String, BigDecimal?> = emptyMap(),
    val maxs: Map<String, BigDecimal?> = emptyMap(),
    /** Order-independent checksum over the Row Fingerprints; null when there are no rows or no fingerprint columns. */
    val checksum: BigDecimal?,
    /** Rows minus distinct keys: zero when the key identifies every row. Null when the Target is keyless. */
    val duplicateKeyRows: Long? = null,
)
