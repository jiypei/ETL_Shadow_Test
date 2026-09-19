package com.etlshadowtest.target

import com.etlshadowtest.api.Location
import com.etlshadowtest.duckdb.Workspace

/** One Environment's copy of a Target, whatever its type. Both sides of a comparison are always the same type. */
interface TargetSide {
    val environment: String

    /** The Target's columns from its real metadata or schema, or null when it does not exist in this Environment. */
    fun columns(location: Location): List<ColumnMeta>?

    fun aggregate(location: Location, spec: AggregateSpec, scope: BoundScope?): AggregateValues

    /**
     * Creates [table] in the workspace with one VARCHAR column per key (k0, k1, ...) holding the key's canonical text,
     * plus `fp`, the Row Fingerprint, for every row in the Comparison Scope.
     */
    fun loadKeyFingerprints(
        location: Location,
        keys: List<ColumnMeta>,
        fingerprint: List<ColumnMeta>,
        scope: BoundScope?,
        workspace: Workspace,
        table: String,
    )

    /** Full rows for the given keys (canonical key text tuples), keyed by that same tuple. Values are JSON-friendly. */
    fun fetchRows(
        location: Location,
        columns: List<ColumnMeta>,
        keys: List<ColumnMeta>,
        keyTuples: List<List<String?>>,
        scope: BoundScope?,
    ): Map<List<String?>, Map<String, Any?>>
}
