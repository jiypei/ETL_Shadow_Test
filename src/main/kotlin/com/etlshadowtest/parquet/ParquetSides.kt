package com.etlshadowtest.parquet

import com.etlshadowtest.config.ShadowProperties
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component

@Component
class ParquetSides(props: ShadowProperties) {
    val staging = ParquetSide("staging", props.staging.minio, props.duckdb.extensionDirectory)
    val production = ParquetSide("production", props.production.minio, props.duckdb.extensionDirectory)

    @PreDestroy
    fun close() { staging.close(); production.close() }
}
