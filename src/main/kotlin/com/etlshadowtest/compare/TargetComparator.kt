package com.etlshadowtest.compare

import com.etlshadowtest.api.ScopeRange
import com.etlshadowtest.api.TargetConfig
import com.etlshadowtest.oracle.OracleSides
import com.etlshadowtest.run.AggregateCheckResult
import com.etlshadowtest.run.RowDiffResult
import com.etlshadowtest.run.RunContext
import com.etlshadowtest.run.TargetResult
import com.etlshadowtest.run.Verdict
import com.etlshadowtest.target.AggregateSpec
import com.etlshadowtest.target.BoundScope
import com.etlshadowtest.target.ColumnCategory
import com.etlshadowtest.target.ColumnMeta
import com.etlshadowtest.target.ScopeValues
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

const val DRIFT_NOTE = "Full-Refresh Target compared whole: a Mismatch may be caused by source drift rather than a code change (ADR 0003)"

/** Compares one Target between Staging and Production. Any failure to complete is ERROR, never PASS. */
@Component
class TargetComparator(private val oracle: OracleSides, private val rowDiff: RowDiffEngine) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun compare(ctx: RunContext, target: TargetConfig, scope: ScopeRange?): TargetResult = try {
        compareOracle(ctx, target, scope)
    } catch (e: Exception) {
        log.warn("Target {} could not be compared", target.name, e)
        TargetResult(target.name, Verdict.ERROR, reason = e.message)
    }

    private fun compareOracle(ctx: RunContext, target: TargetConfig, scope: ScopeRange?): TargetResult {
        val stagingColumns = requireNotNull(oracle.staging.columns(target.staging)) { "Target is missing in Staging" }
        val productionColumns = requireNotNull(oracle.production.columns(target.production)) { "Target is missing in Production" }
        val ignored = target.ignoredColumns.toSet()
        val compared = stagingColumns.filter { it.name !in ignored }
        val schemaDifferences = schemaDifferences(compared, productionColumns.filter { it.name !in ignored })
        if (schemaDifferences.isNotEmpty()) {
            return finish(target, Verdict.FAIL, schemaDifferences = schemaDifferences)
        }

        val bound = boundScope(target, scope, stagingColumns)
        val keys = target.keyColumns.map { name -> stagingColumns.first { it.name == name } }
        val spec = AggregateSpec(
            sumColumns = compared.filter { it.category == ColumnCategory.NUMERIC },
            nullColumns = compared,
            fingerprintColumns = compared,
            keyColumns = keys,
        )
        val stagingValues = oracle.staging.aggregate(target.staging, spec, bound)
        val productionValues = oracle.production.aggregate(target.production, spec, bound)
        val checks = AggregateComparison.compare(spec, stagingValues, productionValues)
        if (checks.all { it.agrees }) {
            return finish(target, Verdict.PASS, AggregateCheckResult(checks), rowDiff = RowDiffResult("SKIPPED", "Aggregate Check agreed"))
        }
        val diff = rowDiff.run(ctx, target, oracle.staging, oracle.production, compared, keys, compared, bound)
        return finish(target, Verdict.FAIL, AggregateCheckResult(checks), rowDiff = diff)
    }

    private fun finish(
        target: TargetConfig,
        verdict: Verdict,
        aggregate: AggregateCheckResult? = null,
        schemaDifferences: List<String> = emptyList(),
        rowDiff: RowDiffResult? = null,
    ) = TargetResult(
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
