package com.etlshadowtest

import com.etlshadowtest.support.ApiClient
import com.etlshadowtest.support.MinioFixtures
import com.etlshadowtest.support.RunGateConfig
import com.etlshadowtest.support.TestEnvironment
import com.etlshadowtest.support.uniqueName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** The same left-behind record that the default 6s threshold calls abandoned is still running under a one-hour threshold. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["shadow.heartbeat-interval=300ms", "shadow.heartbeat-stale-after=1h"],
)
@Import(RunGateConfig::class)
class HeartbeatThresholdTest {
    @LocalServerPort
    private var port: Int = 0

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) = TestEnvironment.registerProperties(registry)
    }

    @Test
    fun `the staleness threshold is configurable`() {
        val pipeline = uniqueName("p")
        val id = "33333333-3333-3333-3333-333333333333"
        MinioFixtures.writeRunJson(pipeline, id, leftBehindRecord(pipeline, id, secondsAgo = 600))
        val polled = ApiClient("http://localhost:$port", "t").poll(pipeline, id)
        assertThat(polled["status"]!!.asText()).isEqualTo("RUNNING")
        assertThat(polled["verdict"]!!.isNull).isTrue()
    }
}
