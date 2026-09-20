package com.etlshadowtest.target

import com.etlshadowtest.api.Location
import com.etlshadowtest.run.RunContext

/** One Environment's copy of a Target, whatever its type. Both sides of a comparison are always the same type. */
interface TargetSide {
    val environment: String

    /** The Target's columns from its real metadata or schema, or null when it does not exist in this Environment. */
    fun columns(location: Location): List<ColumnMeta>?

    /** Reads this Environment's Targets for one Test Run, using the Test Run's DuckDB where a query needs one. */
    fun readerFor(ctx: RunContext): TargetReader
}

/** A [TargetSide] bound to one Test Run: what the Aggregate Check and the Row Diff read from a Target. */
interface TargetReader {
    fun aggregate(location: Location, spec: AggregateSpec, scope: BoundScope?): AggregateValues

    /** Fills [into], in the Test Run's DuckDB, with one row per row of the Target in the Comparison Scope. */
    fun loadKeyFingerprints(location: Location, into: KeyFingerprintTable, keys: List<ColumnMeta>, fingerprint: List<ColumnMeta>, scope: BoundScope?)

    /** Full rows for the given keys (canonical key text tuples), keyed by that same tuple. Values are JSON-friendly. */
    fun fetchRows(location: Location, columns: List<ColumnMeta>, keys: List<ColumnMeta>, keyTuples: List<List<String?>>, scope: BoundScope?): Map<List<String?>, Map<String, Any?>>
}
