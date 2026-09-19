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
    val aggregateCheck: AggregateCheckResult? = null,
)

data class AggregateCheckResult(val checks: List<CheckResult>)

data class CheckResult(
    val check: String,
    val column: String? = null,
    val staging: BigDecimal?,
    val production: BigDecimal?,
    val agrees: Boolean,
)
