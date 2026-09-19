package com.echomind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for the Agent execution stage, including time spent waiting in its queue. */
@ConfigurationProperties(prefix = "echomind.agent-execution")
public class AgentExecutionProperties {

    private int threads = 8;
    private int queueCapacity = 32;
    private long timeoutMs = 30_000;

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("agent-execution.threads must be positive");
        }
        this.threads = threads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("agent-execution.queue-capacity must be positive");
        }
        this.queueCapacity = queueCapacity;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        if (timeoutMs < 1 || timeoutMs > 600_000) {
            throw new IllegalArgumentException("agent-execution.timeout-ms must be between 1 and 600000");
        }
        this.timeoutMs = timeoutMs;
    }
}
