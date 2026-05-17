package com.ecommerce.E_Commerce.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded executor for app-managed async work (Req 2).
 *
 * <p>Numbers are deliberately small to keep behaviour observable on a single
 * machine. See docs/architecture-notes.md for the justification of every
 * value and what happens at each saturation point.
 */
@Configuration
@EnableAsync
public class AsyncExecutorConfig {

    @Bean(name = "appAsyncExecutor")
    public TaskExecutor appAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("app-async-");
        // AbortPolicy on overflow: throws RejectedExecutionException to the
        // caller. We pick this over CallerRunsPolicy on purpose — running the
        // task on the caller's Tomcat thread would defeat the point of moving
        // work off the request path. Failing fast is observable and lets the
        // caller decide (retry, log, return 503).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
