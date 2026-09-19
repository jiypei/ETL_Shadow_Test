package com.etlshadowtest.run

import com.etlshadowtest.config.ShadowProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors

/** Where Test Runs execute, off the request thread. */
fun interface RunExecutor {
    fun execute(task: Runnable)
}

@Configuration
class RunExecutorConfig {
    @Bean("runExecutor", destroyMethod = "")
    fun runExecutor(props: ShadowProperties): RunExecutor {
        val pool = Executors.newFixedThreadPool(props.maxConcurrentRuns.coerceAtLeast(1)) { r ->
            Thread(r, "test-run").apply { isDaemon = true }
        }
        return RunExecutor { pool.execute(it) }
    }
}
