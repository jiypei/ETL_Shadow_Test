package com.etlshadowtest.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties("shadow")
data class ShadowProperties(
    val staging: EnvironmentProperties,
    val production: EnvironmentProperties,
    val results: ResultsProperties,
    @DefaultValue("5s") val heartbeatInterval: Duration,
    @DefaultValue("30s") val heartbeatStaleAfter: Duration,
    /** Test Runs in progress at once. A trigger arriving at the cap is rejected with 429 (ADR 0004). */
    @DefaultValue("2") val maxConcurrentRuns: Int,
    /** What a rejected caller is told to wait before trying again. */
    @DefaultValue("30s") val retryAfter: Duration,
    /** Sampled Mismatches kept per Mismatch type and Target. */
    @DefaultValue("100") val mismatchSampleSize: Int,
    val duckdb: DuckDbProperties,
    /** Caller tokens; with none configured every API call is rejected. */
    val tokens: List<TokenProperties> = emptyList(),
    /** How often a sweep looks for Test Runs whose instance died, to mark them abandoned and send their callback. */
    @DefaultValue("30s") val reaperInterval: Duration,
    @DefaultValue val webhook: WebhookProperties,
)

data class WebhookProperties(
    /** Hosts a callback may be sent to. With none, callbacks are not enabled and a request naming one is rejected. */
    val allowedHosts: List<String> = emptyList(),
    /** Attempts in total, the first included. */
    @DefaultValue("3") val maxAttempts: Int,
    @DefaultValue("1s") val initialBackoff: Duration,
    /** Each pause is this many times the one before: 1s, 5s, then 25s with the defaults. */
    @DefaultValue("5") val backoffMultiplier: Int,
    @DefaultValue("10s") val timeout: Duration,
)

data class TokenProperties(val token: String, val pipeline: String, val targets: List<String>)

data class DuckDbProperties(
    /** Parent of the per-Test-Run working directories; DuckDB spills here. */
    val tempDirectory: String,
    /** DuckDB's memory is off-heap: size the container limit as JVM heap plus this per concurrent Test Run. */
    @DefaultValue("1GB") val memoryLimit: String,
    /** Where DuckDB finds (or installs) its httpfs extension; DuckDB's default when unset. */
    val extensionDirectory: String? = null,
    /** DuckDB threads per Test Run; each thread needs its own buffers, so fewer threads also means less memory. */
    @DefaultValue("2") val threads: Int,
)

/** Connections for one Environment. Only ever set through service configuration. */
data class EnvironmentProperties(val oracle: OracleProperties, val minio: MinioProperties)

data class OracleProperties(
    val url: String,
    val username: String,
    val password: String,
    @DefaultValue("4") val maxConnections: Int,
    /** How long to keep trying to reach the database before the affected Targets are reported as ERROR. */
    @DefaultValue("10s") val connectionTimeout: Duration,
    /** When set, every query goes here instead of [url]. Meant for the Production read replica, so the primary is spared. */
    val replicaUrl: String? = null,
    /** Queries running at once against this database, across all Test Runs (never more than [maxConnections]). */
    @DefaultValue("2") val maxParallelQueries: Int,
    /** A query running longer than this is cancelled and the affected Target is reported as ERROR. */
    @DefaultValue("30m") val queryTimeout: Duration,
)

data class MinioProperties(val endpoint: String, val accessKey: String, val secretKey: String, val bucket: String)

data class ResultsProperties(val minio: MinioProperties)
