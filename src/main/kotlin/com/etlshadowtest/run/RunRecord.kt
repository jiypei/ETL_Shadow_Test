package com.etlshadowtest.run

import com.etlshadowtest.api.ComparisonConfig
import com.etlshadowtest.api.ScopeRange
import java.math.BigDecimal

enum class Verdict { PASS, FAIL, ERROR, SKIPPED }

enum class RunStatus { RUNNING, COMPLETED, ABANDONED }

/** The single object stored at results/{pipeline}/{testRunId}/run.json (ADR 0002). */
data class RunRecord(
    val testRunId: String,
    val pipeline: String,
    val status: RunStatus,
    val startedAt: String,
    val heartbeatAt: String,
    val finishedAt: String? = null,
    val scope: ScopeRange? = null,
    val verdict: Verdict? = null,
    val reason: String? = null,
    val config: ComparisonConfig,
    val targets: List<TargetResult> = emptyList(),
)

data class TargetResult(
    val name: String,
    val verdict: Verdict,
    val reason: String? = null,
    val notes: List<String> = emptyList(),
    val schemaDifferences: List<String> = emptyList(),
    val aggregateCheck: AggregateCheckResult? = null,
    val rowDiff: RowDiffResult? = null,
)

data class AggregateCheckResult(val checks: List<CheckResult>)

data class CheckResult(
    val check: String,
    val column: String? = null,
    val staging: BigDecimal?,
    val production: BigDecimal?,
    val agrees: Boolean,
    val method: String = "exact",
)

/** RAN with counts and a link to the sampled Mismatches, or SKIPPED with the reason. */
data class RowDiffResult(
    val status: String,
    val reason: String? = null,
    val onlyInStaging: Long? = null,
    val onlyInProduction: Long? = null,
    val differentRows: Long? = null,
    val sampleLimitPerType: Int? = null,
    val sampled: Int? = null,
    val mismatchesFile: String? = null,
)
