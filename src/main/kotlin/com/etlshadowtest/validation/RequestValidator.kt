package com.etlshadowtest.validation

import com.etlshadowtest.api.ApiException
import com.etlshadowtest.api.Location
import com.etlshadowtest.api.TargetConfig
import com.etlshadowtest.api.TargetType
import com.etlshadowtest.api.TriggerRequest
import com.etlshadowtest.parquet.DuckSql
import com.etlshadowtest.target.ColumnCategory
import com.etlshadowtest.target.ColumnMeta
import com.etlshadowtest.target.ScopeValues
import com.etlshadowtest.target.TargetSide
import com.etlshadowtest.target.TargetSides
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,100}")

/** Rejects a request up front, before any Test Run exists, with an error that names the problem. */
@Component
class RequestValidator(private val sides: TargetSides) {
    private sealed interface Lookup {
        class Found(val columns: List<ColumnMeta>) : Lookup
        data object Missing : Lookup

        /** The Environment could not be reached, so nothing is known about the Target there. */
        data object Unavailable : Lookup
    }

    fun validate(request: TriggerRequest) {
        val problems = mutableListOf<String>()
        if (!SAFE_NAME.matches(request.pipeline)) problems += "Pipeline name must consist of letters, digits, '.', '_' or '-'"
        if (request.config.targets.isEmpty()) problems += "The Comparison Config must name at least one Target"
        val names = request.config.targets.map { it.name }
        if (names.toSet().size != names.size) problems += "Target names must be unique"

        for (target in request.config.targets) {
            if (!SAFE_NAME.matches(target.name)) {
                problems += "Target name '${target.name}' must consist of letters, digits, '.', '_' or '-'"
                continue
            }
            val shape = locationProblems(target)
            problems += shape
            problems += declarationProblems(target, request)
            if (shape.isEmpty()) problems += metadataProblems(target, request)
        }
        if (problems.isNotEmpty()) throw ApiException(HttpStatus.BAD_REQUEST, "Invalid request: ${problems.first()}", problems)
    }

    private fun locationProblems(target: TargetConfig): List<String> = listOf("Staging" to target.staging, "Production" to target.production).mapNotNull { (env, loc) ->
        val name = target.name
        when (target.type) {
            TargetType.ORACLE -> when {
                loc.path != null -> "Target '$name': the $env location of an Oracle Target is a schema and table, not a path"
                loc.schema.isNullOrBlank() || loc.table.isNullOrBlank() -> "Target '$name': the $env location needs a schema and a table"
                else -> null
            }
            TargetType.PARQUET -> when {
                loc.schema != null || loc.table != null -> "Target '$name': the $env location of a Parquet Target is a path, not a schema and table"
                loc.path.isNullOrBlank() || !DuckSql.isValidPath(loc.path) -> "Target '$name': the $env location needs a valid path (letters, digits, '.', '_', '-', '=' and '/')"
                else -> null
            }
        }
    }

    private fun declarationProblems(target: TargetConfig, request: TriggerRequest): List<String> = buildList {
        if (!target.newTarget) {
            if (target.keyless && target.keyColumns.isNotEmpty()) {
                add("Target '${target.name}' is declared keyless and must not name keyColumns")
            } else if (!target.keyless && target.keyColumns.isEmpty()) {
                add("Target '${target.name}' must name keyColumns or be declared keyless")
            }
        }
        if (target.fullRefresh && target.scopeColumn != null) {
            add("Target '${target.name}' is Full-Refresh and must not name a scope column")
        } else if (!target.fullRefresh && !target.newTarget && target.scopeColumn == null) {
            add("Target '${target.name}' must either name a scope column or be declared a Full-Refresh Target")
        } else if (target.scopeColumn != null && request.scope == null) {
            add("Target '${target.name}' has a scope column, so the request needs a scope range (from, to)")
        }
    }

    /** Checks every column named in the config against the real metadata, from whichever Environment can be read. */
    private fun metadataProblems(target: TargetConfig, request: TriggerRequest): List<String> {
        val staging = lookup(sides.staging(target.type), target.staging)
        val production = lookup(sides.production(target.type), target.production)
        if (staging is Lookup.Missing && production is Lookup.Missing) {
            return listOf("Target '${target.name}' does not exist in Staging or Production: check its location for a typo")
        }
        val columns = (staging as? Lookup.Found)?.columns ?: (production as? Lookup.Found)?.columns ?: return emptyList()
        return buildList {
            addAll(unsupportedTypes(target, columns))
            addAll(toleranceProblems(target, columns))
            val scopeColumn = target.scopeColumn
            if (scopeColumn != null && request.scope != null) scopeProblem(target, scopeColumn, columns, request)?.let { add(it) }
        }
    }

    private fun lookup(side: TargetSide, location: Location): Lookup = try {
        side.columns(location)?.let { Lookup.Found(it) } ?: Lookup.Missing
    } catch (e: Exception) {
        Lookup.Unavailable
    }

    private fun scopeProblem(target: TargetConfig, scopeColumn: String, columns: List<ColumnMeta>, request: TriggerRequest): String? {
        val column = columns.firstOrNull { it.name == scopeColumn } ?: return "Target '${target.name}': scope column '$scopeColumn' does not exist"
        return try {
            ScopeValues.bind(column, request.scope!!); null
        } catch (e: IllegalArgumentException) {
            "Target '${target.name}': ${e.message}"
        }
    }

    /** A column whose type cannot be compared exactly must be declared ignored, never silently skipped. */
    private fun unsupportedTypes(target: TargetConfig, columns: List<ColumnMeta>): List<String> =
        columns.filter { it.category == ColumnCategory.OTHER && it.name !in target.ignoredColumns }.map {
            "Target '${target.name}': column '${it.name}' has type ${it.dataType}, which cannot be compared; add it to ignoredColumns"
        }

    private fun toleranceProblems(target: TargetConfig, columns: List<ColumnMeta>): List<String> =
        target.tolerances.mapNotNull { (name, tolerance) ->
            val column = columns.firstOrNull { it.name == name }
            when {
                tolerance.signum() < 0 -> "Target '${target.name}': tolerance for column '$name' must not be negative"
                column == null -> "Target '${target.name}': tolerance column '$name' does not exist"
                column.category != ColumnCategory.NUMERIC -> "Target '${target.name}': tolerance column '$name' is not numeric (${column.dataType})"
                name in target.keyColumns -> "Target '${target.name}': key column '$name' cannot have a tolerance"
                name in target.ignoredColumns -> "Target '${target.name}': ignored column '$name' cannot have a tolerance"
                else -> null
            }
        }
}
