package com.etlshadowtest

import com.etlshadowtest.config.ShadowProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(ShadowProperties::class)
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
