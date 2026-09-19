package com.etlshadowtest.support

import com.etlshadowtest.auth.Principal
import com.etlshadowtest.auth.TokenStore
import com.etlshadowtest.run.RunExecutor
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** Lets a test hold every Test Run before it starts comparing, so in-progress behaviour can be observed. */
class RunGate {
    @Volatile
    private var latch: CountDownLatch? = null

    fun hold() { latch = CountDownLatch(1) }

    fun release() { latch?.countDown(); latch = null }

    fun await() { latch?.await() }
}

@TestConfiguration
class RunGateConfig {
    @Bean
    fun runGate() = RunGate()

    /** The API tests are not about who is calling: any token may act for any Pipeline and Target. */
    @Bean
    @Primary
    fun permissiveTokens(): TokenStore = TokenStore {
        object : Principal {
            override fun canAccess(pipeline: String) = true
            override fun canUseTarget(name: String) = true
        }
    }

    @Bean
    @Primary
    fun gatedRunExecutor(gate: RunGate, @Qualifier("runExecutor") real: RunExecutor): RunExecutor =
        RunExecutor { task -> real.execute { gate.await(); task.run() } }
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "shadow.heartbeat-interval=300ms",
        "shadow.heartbeat-stale-after=6s",
        // Generous, so that Test Runs left over from an earlier test never make a later test hit the cap.
        "shadow.max-concurrent-runs=16",
    ],
)
@Import(RunGateConfig::class)
abstract class ShadowTestBase {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var gate: RunGate

    protected val api: ApiClient by lazy { ApiClient("http://localhost:$port", tokenFor(null) ?: "test-token") }

    protected open fun tokenFor(pipeline: String?): String? = null

    @AfterEach
    fun openGate() = gate.release()

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) = TestEnvironment.registerProperties(registry)
    }
}

fun uniqueName(prefix: String) = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()

/** An Oracle Target. Full-Refresh unless the caller gives it a scope column, and keyed on ID unless told otherwise. */
fun oracleTarget(
    name: String,
    stagingTable: String,
    productionTable: String = stagingTable,
    extra: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = mapOf(
    "name" to name,
    "type" to "ORACLE",
    "staging" to mapOf("schema" to TestEnvironment.STAGING_OWNER, "table" to stagingTable),
    "production" to mapOf("schema" to TestEnvironment.PRODUCTION_OWNER, "table" to productionTable),
) + (if ("scopeColumn" in extra) emptyMap() else mapOf("fullRefresh" to true)) +
    (if ("keyless" in extra) emptyMap() else mapOf("keyColumns" to listOf("ID"))) + extra

fun shadowRequest(
    pipeline: String,
    vararg targets: Map<String, Any?>,
    scope: Map<String, Any?>? = null,
    callbackUrl: String? = null,
): Map<String, Any?> = buildMap {
    put("pipeline", pipeline)
    if (scope != null) put("scope", scope)
    if (callbackUrl != null) put("callbackUrl", callbackUrl)
    put("config", mapOf("targets" to targets.toList()))
}
