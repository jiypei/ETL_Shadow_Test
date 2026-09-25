package com.etlshadowtest.parquet

import com.etlshadowtest.config.ShadowProperties
import com.etlshadowtest.duckdb.DuckDbMetrics
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component

@Component
class ParquetSides(props: ShadowProperties, metrics: DuckDbMetrics) {
    val staging = ParquetSide("staging", props.staging.minio, props.duckdb.extensionDirectory, metrics)
    val production = ParquetSide("production", props.production.minio, props.duckdb.extensionDirectory, metrics)

    @PreDestroy
    fun close() { staging.close(); production.close() }
}
