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
    /** Targets that were skipped (New Targets): they are not part of the Verdict and nothing was checked for them. */
    val unverifiedTargets: List<String> = emptyList(),
    val callbackUrl: String? = null,
    /** What became of the callback; null when the trigger named none. */
    val callback: CallbackResult? = null,
)

data class TargetResult(
    val name: String,
    val verdict: Verdict,
    val reason: String? = null,
    val notes: List<String> = emptyList(),
    val schemaDifferences: List<String> = emptyList(),
    val aggregateCheck: AggregateCheckResult? = null,
    val rowDiff: RowDiffResult? = null,
    val toleranceColumns: List<ToleranceReport> = emptyList(),
)

/** How a tolerance column was checked, so nobody assumes it was compared row by row. */
data class ToleranceReport(val column: String, val tolerance: BigDecimal, val checkedBy: String)

data class AggregateCheckResult(val checks: List<CheckResult>)

data class CheckResult(
    val check: String,
    val column: String? = null,
    val staging: BigDecimal?,
    val production: BigDecimal?,
    val agrees: Boolean,
    /** "exact", "tolerance", or "informational" (shown but not deciding, see [decisive]). */
    val method: String = "exact",
    val tolerance: BigDecimal? = null,
    /**
     * False for a check whose disagreement proves nothing, such as the sum of a floating-point column: its exactness is
     * decided by the checksum over every value, so this one is reported for the size of a difference only.
     */
    val decisive: Boolean = true,
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
    val duplicateKeys: DuplicateKeysResult? = null,
    /** Keyless Targets only: differences in how often identical rows occur. */
    val countDifferences: CountDifferencesResult? = null,
    val note: String? = null,
)

data class DuplicateKeysResult(val staging: DuplicateKeysSide, val production: DuplicateKeysSide)

data class DuplicateKeysSide(val keys: Long, val sample: List<DuplicateKey>)

data class DuplicateKey(val key: Map<String, Any?>, val occurrences: Long)

data class CountDifferencesResult(
    val differingRowContents: Long,
    val rowsExtraInStaging: Long,
    val rowsExtraInProduction: Long,
    val sample: List<CountDifference>,
)

/** A Row Fingerprint (a stand-in for one distinct row content) and how often it occurs on each side. */
data class CountDifference(val fingerprint: String, val staging: Long, val production: Long)

/** One line of a Pipeline's history: what a release manager needs to judge readiness. */
data class HistoryEntry(
    val testRunId: String,
    val status: RunStatus,
    val startedAt: String,
    val finishedAt: String?,
    /** Not part of the answer; used to tell a Test Run that is still alive from one that was abandoned. */
    @get:com.fasterxml.jackson.annotation.JsonIgnore val heartbeatAt: String,
    val scope: ScopeRange?,
    val verdict: Verdict?,
    val reason: String?,
    val targets: List<TargetVerdict>,
    val unverifiedTargets: List<String>,
)

data class TargetVerdict(val name: String, val verdict: Verdict)

data class History(val pipeline: String, val testRuns: List<HistoryEntry>)

/** PENDING until delivered (2xx) or given up on after the configured attempts. */
data class CallbackResult(val status: String, val attempts: Int, val lastError: String? = null, val deliveredAt: String? = null)
