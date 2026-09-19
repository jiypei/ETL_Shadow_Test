package com.etlshadowtest.run

import com.etlshadowtest.duckdb.Workspace

/** Per-Test-Run resources. The DuckDB working space is created on first use and always removed when the Test Run ends. */
class RunContext(
    val testRunId: String,
    val pipeline: String,
    private val workspaceFactory: (String) -> Workspace,
) : AutoCloseable {
    private var workspace: Workspace? = null

    @Synchronized
    fun workspace(): Workspace = workspace ?: workspaceFactory(testRunId).also { workspace = it }

    @Synchronized
    override fun close() {
        workspace?.close()
        workspace = null
    }
}
