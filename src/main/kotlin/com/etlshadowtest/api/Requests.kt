package com.etlshadowtest.api

import java.math.BigDecimal

enum class TargetType { ORACLE, PARQUET }

data class TriggerRequest(
    val pipeline: String,
    val config: ComparisonConfig,
    val scope: ScopeRange? = null,
    val callbackUrl: String? = null,
)

data class ComparisonConfig(val targets: List<TargetConfig>)

/** Per-Target declaration. Everything here is a declared name or value, never SQL. */
data class TargetConfig(
    val name: String,
    val type: TargetType,
    val staging: Location,
    val production: Location,
    val keyColumns: List<String> = emptyList(),
    val ignoredColumns: List<String> = emptyList(),
    val tolerances: Map<String, BigDecimal> = emptyMap(),
    val scopeColumn: String? = null,
    val fullRefresh: Boolean = false,
    val keyless: Boolean = false,
    val newTarget: Boolean = false,
)

/** Where a Target lives in one Environment: schema and table for Oracle, a path in the Environment's bucket for Parquet. */
data class Location(val schema: String? = null, val table: String? = null, val path: String? = null)

/** Half-open range [from, to) of the scope column that makes up the Comparison Scope. */
data class ScopeRange(val from: String, val to: String)
