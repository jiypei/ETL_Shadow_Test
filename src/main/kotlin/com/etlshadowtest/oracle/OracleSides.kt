package com.etlshadowtest.oracle

import com.etlshadowtest.config.ShadowProperties
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component

@Component
class OracleSides(props: ShadowProperties) {
    val staging = OracleSide("staging", props.staging.oracle)
    val production = OracleSide("production", props.production.oracle)

    @PreDestroy
    fun close() { staging.close(); production.close() }
}
