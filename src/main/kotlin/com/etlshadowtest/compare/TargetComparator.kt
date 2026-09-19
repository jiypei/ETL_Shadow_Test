package com.etlshadowtest.compare

import com.etlshadowtest.api.ScopeRange
import com.etlshadowtest.api.TargetConfig
import com.etlshadowtest.oracle.OracleSides
import com.etlshadowtest.run.AggregateCheckResult
import com.etlshadowtest.run.CheckResult
import com.etlshadowtest.run.TargetResult
import com.etlshadowtest.run.Verdict
import com.etlshadowtest.target.BoundScope
import com.etlshadowtest.target.ScopeValues
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

const val DRIFT_NOTE = "Full-Refresh Target compared whole: a Mismatch may be caused by source drift rather than a code change (ADR 0003)"

/** Compares one Target between Staging and Production. Any failure to complete is ERROR, never PASS. */
@Component
class TargetComparator(private val oracle: OracleSides) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun compare(target: TargetConfig, scope: ScopeRange?): TargetResult = try {
        val bound = boundScope(target, scope)
        val staging = oracle.staging.rowCount(target.staging, bound)
        val production = oracle.production.rowCount(target.production, bound)
        val agrees = staging == production
        val verdict = if (agrees) Verdict.PASS else Verdict.FAIL
        TargetResult(
            name = target.name,
            verdict = verdict,
            notes = if (verdict == Verdict.FAIL && target.fullRefresh) listOf(DRIFT_NOTE) else emptyList(),
            aggregateCheck = AggregateCheckResult(
                listOf(CheckResult("row_count", null, staging.toBigDecimal(), production.toBigDecimal(), agrees)),
            ),
        )
    } catch (e: Exception) {
        log.warn("Target {} could not be compared", target.name, e)
        TargetResult(target.name, Verdict.ERROR, reason = e.message)
    }

    private fun boundScope(target: TargetConfig, scope: ScopeRange?): BoundScope? {
        if (target.fullRefresh) return null
        val column = requireNotNull(oracle.staging.columns(target.staging)) { "Staging table missing" }
            .first { it.name == target.scopeColumn }
        return ScopeValues.bind(column, requireNotNull(scope))
    }
}
