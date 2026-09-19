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
    @DefaultValue("2") val maxConcurrentRuns: Int,
    /** Sampled Mismatches kept per Mismatch type and Target. */
    @DefaultValue("100") val mismatchSampleSize: Int,
    val duckdb: DuckDbProperties,
    /** Caller tokens; with none configured every API call is rejected. */
    val tokens: List<TokenProperties> = emptyList(),
)

data class TokenProperties(val token: String, val pipeline: String, val targets: List<String>)

data class DuckDbProperties(
    /** Parent of the per-Test-Run working directories; DuckDB spills here. */
    val tempDirectory: String,
    /** DuckDB's memory is off-heap: size the container limit as JVM heap plus this per concurrent Test Run. */
    @DefaultValue("1GB") val memoryLimit: String,
    /** Where DuckDB finds (or installs) its httpfs extension; DuckDB's default when unset. */
    val extensionDirectory: String? = null,
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
)

data class MinioProperties(val endpoint: String, val accessKey: String, val secretKey: String, val bucket: String)

data class ResultsProperties(val minio: MinioProperties)
