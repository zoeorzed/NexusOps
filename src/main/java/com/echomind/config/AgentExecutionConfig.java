package com.echomind.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableConfigurationProperties(AgentExecutionProperties.class)
public class AgentExecutionConfig {

    // Keep Boot's default @Async executor available for independent profile updates.
    @Bean(name = "agentExecutor", defaultCandidate = false, destroyMethod = "shutdownNow")
    public ThreadPoolExecutor agentExecutor(AgentExecutionProperties properties) {
        return newExecutor(properties);
    }

    public static ThreadPoolExecutor newExecutor(AgentExecutionProperties properties) {
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                properties.getThreads(), properties.getThreads(), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                task -> {
                    Thread thread = new Thread(task, "echomind-agent-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                // Never run blocked model calls on the request thread when saturated.
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
