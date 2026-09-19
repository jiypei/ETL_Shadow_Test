package com.etlshadowtest.validation

import com.etlshadowtest.api.ApiException
import com.etlshadowtest.api.TargetConfig
import com.etlshadowtest.api.TriggerRequest
import com.etlshadowtest.oracle.OracleSides
import com.etlshadowtest.target.ColumnCategory
import com.etlshadowtest.target.ScopeValues
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/** Rejects a request up front, before any Test Run exists, with an error that names the problem. */
@Component
class RequestValidator(private val oracle: OracleSides) {
    fun validate(request: TriggerRequest) {
        val problems = mutableListOf<String>()
        for (target in request.config.targets) {
            if (!target.newTarget) {
                if (target.keyless && target.keyColumns.isNotEmpty()) {
                    problems += "Target '${target.name}' is declared keyless and must not name keyColumns"
                } else if (!target.keyless && target.keyColumns.isEmpty()) {
                    problems += "Target '${target.name}' must name keyColumns or be declared keyless"
                }
            }
            val scopeColumn = target.scopeColumn
            if (target.fullRefresh && scopeColumn != null) {
                problems += "Target '${target.name}' is Full-Refresh and must not name a scope column"
            } else if (!target.fullRefresh && !target.newTarget && scopeColumn == null) {
                problems += "Target '${target.name}' must either name a scope column or be declared a Full-Refresh Target"
            } else if (scopeColumn != null) {
                if (request.scope == null) {
                    problems += "Target '${target.name}' has a scope column, so the request needs a scope range (from, to)"
                } else {
                    scopeProblem(target.name, target, scopeColumn, request)?.let { problems += it }
                }
            }
        }
        for (target in request.config.targets) problems += unsupportedTypes(target)
        if (problems.isNotEmpty()) throw ApiException(HttpStatus.BAD_REQUEST, "Invalid request: ${problems.first()}", problems)
    }

    private fun scopeProblem(name: String, target: TargetConfig, scopeColumn: String, request: TriggerRequest): String? {
        val columns = oracle.staging.columns(target.staging) ?: oracle.production.columns(target.production) ?: return null
        val column = columns.firstOrNull { it.name == scopeColumn }
            ?: return "Target '$name': scope column '$scopeColumn' does not exist"
        return try {
            ScopeValues.bind(column, request.scope!!); null
        } catch (e: IllegalArgumentException) {
            "Target '$name': ${e.message}"
        }
    }

    /** A column whose type cannot be compared exactly must be declared ignored, never silently skipped. */
    private fun unsupportedTypes(target: TargetConfig): List<String> {
        val columns = oracle.staging.columns(target.staging) ?: oracle.production.columns(target.production) ?: return emptyList()
        return columns.filter { it.category == ColumnCategory.OTHER && it.name !in target.ignoredColumns }.map {
            "Target '${target.name}': column '${it.name}' has type ${it.dataType}, which cannot be compared; add it to ignoredColumns"
        }
    }
}
