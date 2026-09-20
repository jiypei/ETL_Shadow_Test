package com.etlshadowtest.compare

import com.etlshadowtest.api.ScopeRange
import com.etlshadowtest.api.TargetConfig
import com.etlshadowtest.run.AggregateCheckResult
import com.etlshadowtest.run.RowDiffResult
import com.etlshadowtest.run.RunContext
import com.etlshadowtest.run.TargetResult
import com.etlshadowtest.run.ToleranceReport
import com.etlshadowtest.run.Verdict
import com.etlshadowtest.target.AggregateSpec
import com.etlshadowtest.target.BoundScope
import com.etlshadowtest.target.ColumnMeta
import com.etlshadowtest.target.ScopeValues
import com.etlshadowtest.target.TargetSides
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

const val DRIFT_NOTE = "Full-Refresh Target compared whole: a Mismatch may be caused by source drift rather than a code change (ADR 0003)"

const val NEW_TARGET_REASON = "New Target: there is no Production counterpart yet, so this Target was not compared and is unverified"

const val TOLERANCE_STATEMENT = "Aggregate Check only: the sum (allowing the tolerance times the row count), the minimum and the maximum are compared " +
    "within the tolerance. The column is excluded from the Row Fingerprint and is not compared row by row, so a difference in individual " +
    "rows that leaves these aggregates within the tolerance is not detected (ADR 0001)"

/** Compares one Target between Staging and Production. Any failure to complete is ERROR, never PASS. */
@Component
class TargetComparator(private val sides: TargetSides, private val rowDiff: RowDiffEngine) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun compare(ctx: RunContext, target: TargetConfig, scope: ScopeRange?): TargetResult = try {
        if (target.newTarget) {
            TargetResult(target.name, Verdict.SKIPPED, reason = NEW_TARGET_REASON)
        } else {
            compareTarget(ctx, target, scope)
        }
    } catch (e: Exception) {
        log.warn("Target {} could not be compared", target.name, e)
        TargetResult(target.name, Verdict.ERROR, reason = e.message)
    }

    private fun compareTarget(ctx: RunContext, target: TargetConfig, scope: ScopeRange?): TargetResult {
        val staging = sides.staging(target.type)
        val production = sides.production(target.type)
        val stagingColumns = requireNotNull(staging.columns(target.staging)) { "Target is missing in Staging" }
        val productionColumns = requireNotNull(production.columns(target.production)) { "Target is missing in Production" }
        val ignored = target.ignoredColumns.toSet()
        val compared = stagingColumns.filter { it.name !in ignored }
        val schemaDifferences = schemaDifferences(compared, productionColumns.filter { it.name !in ignored })
        if (schemaDifferences.isNotEmpty()) {
            return finish(target, Verdict.FAIL, schemaDifferences = schemaDifferences)
        }

        val bound = boundScope(target, scope, stagingColumns)
        val keys = target.keyColumns.map { name -> stagingColumns.first { it.name == name } }
        val toleranceColumns = compared.filter { it.name in target.tolerances }
        val fingerprint = compared.filter { it.name !in target.tolerances }
        val spec = AggregateSpec(
            sumColumns = compared.filter { it.category.numeric },
            nullColumns = compared,
            fingerprintColumns = fingerprint,
            keyColumns = keys,
            toleranceColumns = toleranceColumns,
            tolerances = target.tolerances,
        )
        val stagingValues = staging.aggregate(target.staging, spec, bound, ctx)
        val productionValues = production.aggregate(target.production, spec, bound, ctx)
        val checks = AggregateComparison.compare(spec, stagingValues, productionValues)
        if (AggregateComparison.agree(checks)) {
            return finish(target, Verdict.PASS, AggregateCheckResult(checks), rowDiff = RowDiffResult("SKIPPED", "Aggregate Check agreed"))
        }
        val diff = rowDiff.run(ctx, target, staging, production, compared, keys, fingerprint, bound, target.tolerances)
        return finish(target, Verdict.FAIL, AggregateCheckResult(checks), rowDiff = diff)
    }

    private fun finish(
        target: TargetConfig,
        verdict: Verdict,
        aggregate: AggregateCheckResult? = null,
        schemaDifferences: List<String> = emptyList(),
        rowDiff: RowDiffResult? = null,
    ) = TargetResult(
        toleranceColumns = target.tolerances.map { (column, tolerance) -> ToleranceReport(column, tolerance, TOLERANCE_STATEMENT) },
        name = target.name,
        verdict = verdict,
        notes = if (verdict == Verdict.FAIL && target.fullRefresh) listOf(DRIFT_NOTE) else emptyList(),
        schemaDifferences = schemaDifferences,
        aggregateCheck = aggregate,
        rowDiff = rowDiff,
    )

    private fun schemaDifferences(staging: List<ColumnMeta>, production: List<ColumnMeta>): List<String> {
        val s = staging.associateBy { it.name }
        val p = production.associateBy { it.name }
        return buildList {
            (s.keys - p.keys).sorted().forEach { add("Column $it exists in Staging only") }
            (p.keys - s.keys).sorted().forEach { add("Column $it exists in Production only") }
            (s.keys intersect p.keys).sorted().forEach {
                if (s.getValue(it).dataType != p.getValue(it).dataType) {
                    add("Column $it has type ${s.getValue(it).dataType} in Staging and ${p.getValue(it).dataType} in Production")
                }
            }
        }
    }

    private fun boundScope(target: TargetConfig, scope: ScopeRange?, columns: List<ColumnMeta>): BoundScope? {
        if (target.fullRefresh) return null
        return ScopeValues.bind(columns.first { it.name == target.scopeColumn }, requireNotNull(scope))
    }
}
