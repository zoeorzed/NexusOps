package com.echomind.agent;

import com.echomind.config.AgentExecutionConfig;
import com.echomind.config.AgentExecutionProperties;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;
import com.echomind.llm.LlmGateway;
import com.echomind.trace.RequestTraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class AgentOrchestratorConcurrencyTest {

    private final List<ThreadPoolExecutor> executors = new ArrayList<>();

    @AfterEach
    void stopExecutors() {
        executors.forEach(ThreadPoolExecutor::shutdownNow);
    }

    @Test
    void dedicatedAgentExecutorDoesNotReplaceDefaultAsyncExecutor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AgentExecutionConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("applicationTaskExecutor").hasBean("agentExecutor");
                    assertThat(context.getBean("agentExecutor"))
                            .isNotSameAs(context.getBean("applicationTaskExecutor"));
                });
    }

    @Test
    void keepsCompletedSiblingWhenPrimaryIgnoresInterruptionAndTimesOut() throws Exception {
        CountDownLatch releasePrimary = new CountDownLatch(1);
        CountDownLatch primaryStarted = new CountDownLatch(1);
        CountDownLatch primaryFinished = new CountDownLatch(1);
        AtomicReference<String> workerName = new AtomicReference<>();
        RequestTraceStore trace = new RequestTraceStore();
        ThreadPoolExecutor executor = executor(2, 2);
        BaseAgent primary = agent(AgentType.TECHNICAL, () -> {
            workerName.set(Thread.currentThread().getName());
            primaryStarted.countDown();
            awaitIgnoringInterrupts(releasePrimary);
            primaryFinished.countDown();
            return success(AgentType.TECHNICAL, "late response must not be returned");
        });
        BaseAgent sibling = agent(AgentType.BILLING, () -> success(AgentType.BILLING, "账务答复已完成"));
        AgentOrchestrator orchestrator = orchestrator(executor, 600, trace, primary, sibling);

        try {
            OrchestratorResult result = orchestrator.run(compositeRequest());

            assertThat(primaryStarted.getCount()).isZero();
            assertThat(primaryFinished.getCount()).isOne();
            assertThat(workerName.get()).startsWith("echomind-agent-");
            assertThat(result.response()).contains("## 账务问题\n\n账务答复已完成", "技术问题处理超时");
            assertThat(result.response()).doesNotContain("late response", "Agent", "billing", "technical", "主处理", "辅助处理");
            assertThat(result.agentTypes()).containsExactly(AgentType.BILLING);
            assertThat(result.toolCalls()).anySatisfy(call -> {
                assertThat(call.toolName()).isEqualTo("agent_execution:technical");
                assertThat(call.error()).isEqualTo("timeout");
                assertThat(call.success()).isFalse();
            });
            assertThat(trace.find("req-concurrency").orElseThrow().toolCalls()).isEqualTo(result.toolCalls());
        } finally {
            releasePrimary.countDown();
            assertThat(primaryFinished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void catchesExceptionalSiblingWithoutLosingSuccessfulAnswer() {
        AgentOrchestrator orchestrator = orchestrator(executor(2, 2), 2_000, new RequestTraceStore(),
                agent(AgentType.TECHNICAL, () -> {
                    throw new IllegalStateException("internal diagnostic must not leak");
                }),
                agent(AgentType.BILLING, () -> success(AgentType.BILLING, "账务答复已完成")));

        OrchestratorResult result = orchestrator.run(compositeRequest());

        assertThat(result.response()).contains("账务答复已完成", "技术问题暂时处理失败");
        assertThat(result.response()).doesNotContain("internal diagnostic", "Agent", "billing", "technical", "主处理", "辅助处理");
        assertThat(result.toolCalls()).anySatisfy(call -> assertThat(call.error()).isEqualTo("failed"));
    }

    @Test
    void returnsExplicitFailureWhenEveryAgentThrows() {
        AgentOrchestrator orchestrator = orchestrator(executor(2, 2), 2_000, new RequestTraceStore(),
                agent(AgentType.TECHNICAL, () -> { throw new IllegalStateException("technical down"); }),
                agent(AgentType.BILLING, () -> { throw new IllegalStateException("billing down"); }));

        OrchestratorResult result = orchestrator.run(compositeRequest());

        assertThat(result.response()).contains("暂时无法完成这些问题的处理", "技术问题暂时处理失败", "账务问题暂时处理失败");
        assertThat(result.response()).doesNotContain("Agent", "technical", "billing");
        assertThat(result.toolCalls()).hasSize(2).allSatisfy(call -> {
            assertThat(call.success()).isFalse();
            assertThat(call.error()).isEqualTo("failed");
        });
    }

    @Test
    void recordsRealBaseAgentFailureAndPreservesCompletedDomainWithoutGeneralRetry() {
        AtomicBoolean generalInvoked = new AtomicBoolean();
        String billingAnswer = "账务答复已完成。\n\n请核对两笔扣款的交易时间。\n\n保留付款凭证，供后续人工核验。";
        LlmGateway failingLlm = (system, prompt, temperature, maxTokens) -> {
            throw new IllegalStateException("private backend diagnostic");
        };
        RequestTraceStore trace = new RequestTraceStore();
        AgentOrchestrator orchestrator = orchestrator(executor(2, 2), 2_000, trace,
                new TechnicalAgent(failingLlm, null),
                new BillingAgent((system, prompt, temperature, maxTokens) -> billingAnswer, null),
                new GeneralAgent((system, prompt, temperature, maxTokens) -> {
                    generalInvoked.set(true);
                    return "unrequested domain retry";
                }, null));

        OrchestratorResult result = orchestrator.run(compositeRequest());

        assertThat(result.response()).isEqualTo("## 账务问题\n\n" + billingAnswer
                + "\n\n技术问题暂时处理失败，请稍后重试。");
        assertThat(result.response()).doesNotContain("private backend", "unrequested domain retry", "Agent", "technical", "billing");
        assertThat(generalInvoked).isFalse();
        assertThat(result.agentTypes()).containsExactly(AgentType.BILLING);
        assertThat(trace.find("req-concurrency").orElseThrow().primaryAgent()).isEqualTo("technical");
        assertThat(trace.find("req-concurrency").orElseThrow().supportingAgents()).containsExactly("billing");
        assertThat(trace.find("req-concurrency").orElseThrow().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("agent_execution:technical");
            assertThat(call.error()).isEqualTo("failed");
            assertThat(call.fallbackUsed()).isFalse();
            assertThat(call.success()).isFalse();
        });
    }

    @Test
    void recordsEveryDomainWhenRealBaseAgentsReturnFailure() {
        LlmGateway failingLlm = (system, prompt, temperature, maxTokens) -> {
            throw new IllegalStateException("private backend diagnostic");
        };
        RequestTraceStore trace = new RequestTraceStore();
        AgentOrchestrator orchestrator = orchestrator(executor(2, 2), 2_000, trace,
                new TechnicalAgent(failingLlm, null), new BillingAgent(failingLlm, null));

        OrchestratorResult result = orchestrator.run(compositeRequest());

        assertThat(result.response()).isEqualTo("抱歉，暂时无法完成这些问题的处理。"
                + "\n\n技术问题暂时处理失败，请稍后重试。\n账务问题暂时处理失败，请稍后重试。");
        assertThat(result.response()).doesNotContain("private backend diagnostic", "Agent", "technical", "billing");
        assertThat(result.agentTypes()).containsExactly(AgentType.TECHNICAL, AgentType.BILLING);
        assertThat(trace.find("req-concurrency").orElseThrow().toolCalls())
                .extracting(call -> call.toolName()).containsExactly("agent_execution:technical", "agent_execution:billing");
        assertThat(trace.find("req-concurrency").orElseThrow().toolCalls()).hasSize(2).allSatisfy(call -> {
            assertThat(call.success()).isFalse();
            assertThat(call.fallbackUsed()).isFalse();
            assertThat(call.error()).isEqualTo("failed");
        });
    }

    @Test
    void queueWaitingConsumesDeadlineAndExpiredTaskNeverInvokesAgent() throws Exception {
        ThreadPoolExecutor executor = executor(1, 1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicBoolean invoked = new AtomicBoolean();
        executor.submit(() -> {
            blockerStarted.countDown();
            awaitIgnoringInterrupts(releaseBlocker);
        });
        assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        RequestTraceStore trace = new RequestTraceStore();
        AgentOrchestrator orchestrator = orchestrator(executor, 200, trace,
                agent(AgentType.TECHNICAL, () -> {
                    invoked.set(true);
                    return success(AgentType.TECHNICAL, "should not run");
                }));

        try {
            OrchestratorResult result = orchestrator.run(singleRequest());

            assertThat(result.response()).isEqualTo("技术问题处理超时，请稍后重试。");
            assertThat(trace.find("req-single").orElseThrow().toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.toolName()).isEqualTo("agent_execution:technical");
                assertThat(call.error()).isEqualTo("timeout");
                assertThat(call.success()).isFalse();
            });
            assertThat(invoked).isFalse();
            assertThat(executor.getQueue()).isEmpty();
            releaseBlocker.countDown();
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertThat(invoked).isFalse();
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void saturatedExecutorRejectsSingleAgentWithoutRunningOnCaller() throws Exception {
        ThreadPoolExecutor executor = executor(1, 1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicBoolean invoked = new AtomicBoolean();
        executor.submit(() -> {
            blockerStarted.countDown();
            awaitIgnoringInterrupts(releaseBlocker);
        });
        assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        executor.submit(() -> { });
        RequestTraceStore trace = new RequestTraceStore();
        AgentOrchestrator orchestrator = orchestrator(executor, 2_000, trace,
                agent(AgentType.TECHNICAL, () -> {
                    invoked.set(true);
                    return success(AgentType.TECHNICAL, "should not run");
                }));

        try {
            OrchestratorResult result = orchestrator.run(singleRequest());

            assertThat(result.response()).isEqualTo("技术问题处理服务繁忙，请稍后重试。");
            assertThat(result.response()).doesNotContain("Agent", "technical", "billing");
            assertThat(invoked).isFalse();
            assertThat(result.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.toolName()).isEqualTo("agent_execution:technical");
                assertThat(call.error()).isEqualTo("rejected");
                assertThat(call.success()).isFalse();
            });
            assertThat(trace.find("req-single").orElseThrow().toolCalls()).isEqualTo(result.toolCalls());
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void keepsAcceptedPrimaryWhenOnlySupportingSubmissionIsRejected() throws Exception {
        ThreadPoolExecutor executor = executor(1, 1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicBoolean billingInvoked = new AtomicBoolean();
        executor.submit(() -> {
            blockerStarted.countDown();
            awaitIgnoringInterrupts(releaseBlocker);
        });
        assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        executor.setRejectedExecutionHandler((task, pool) -> {
            // The primary is now queued; free the worker only after its sibling was rejected.
            releaseBlocker.countDown();
            throw new RejectedExecutionException("saturated");
        });
        AgentOrchestrator orchestrator = orchestrator(executor, 2_000, new RequestTraceStore(),
                agent(AgentType.TECHNICAL, () -> success(AgentType.TECHNICAL, "技术答复已完成")),
                agent(AgentType.BILLING, () -> {
                    billingInvoked.set(true);
                    return success(AgentType.BILLING, "must not run");
                }));

        try {
            OrchestratorResult result = orchestrator.run(compositeRequest());

            assertThat(result.response()).contains("## 技术问题\n\n技术答复已完成", "账务问题处理服务繁忙");
            assertThat(result.response()).doesNotContain("Agent", "technical", "billing", "主处理", "辅助处理");
            assertThat(result.agentTypes()).containsExactly(AgentType.TECHNICAL);
            assertThat(billingInvoked).isFalse();
            assertThat(result.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.toolName()).isEqualTo("agent_execution:billing");
                assertThat(call.error()).isEqualTo("rejected");
            });
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void twoBlockedAgentsShareOneDeadline() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<AgentResponse> blocked = () -> {
            bothStarted.countDown();
            awaitIgnoringInterrupts(release);
            return success(AgentType.GENERAL, "late");
        };
        AgentOrchestrator orchestrator = orchestrator(executor(2, 2), 600, new RequestTraceStore(),
                agent(AgentType.TECHNICAL, blocked), agent(AgentType.BILLING, blocked));

        try {
            long started = System.nanoTime();
            OrchestratorResult result = orchestrator.run(compositeRequest());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(bothStarted.getCount()).isZero();
            assertThat(elapsedMs).isLessThan(1_100L);
            assertThat(result.toolCalls()).hasSize(2).allSatisfy(call ->
                    assertThat(call.error()).isEqualTo("timeout"));
        } finally {
            release.countDown();
        }
    }

    private ThreadPoolExecutor executor(int threads, int queueCapacity) {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setThreads(threads);
        properties.setQueueCapacity(queueCapacity);
        ThreadPoolExecutor executor = AgentExecutionConfig.newExecutor(properties);
        executors.add(executor);
        return executor;
    }

    private AgentOrchestrator orchestrator(ThreadPoolExecutor executor, long timeoutMs,
                                           RequestTraceStore trace, BaseAgent... agents) {
        Map<AgentType, List<BaseAgent>> pool = new java.util.EnumMap<>(AgentType.class);
        for (BaseAgent agent : agents) {
            pool.put(agent.type(), List.of(agent));
        }
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setTimeoutMs(timeoutMs);
        return new AgentOrchestrator(null, pool, trace, executor, properties);
    }

    private BaseAgent agent(AgentType type, Supplier<AgentResponse> action) {
        return new BaseAgent(null, null) {
            @Override
            public AgentType type() { return type; }

            @Override
            protected String systemPrompt() { return ""; }

            @Override
            public AgentResponse handle(AgentRequest request) { return action.get(); }
        };
    }

    private AgentResponse success(AgentType type, String content) {
        return new AgentResponse(type, content, true, 1, 0, false, "", true, false, false, "");
    }

    private AgentRequest compositeRequest() {
        return new AgentRequest("登录失败提示401，而且重复扣款需要退款", "user", "conversation", "", List.of(),
                Map.of("error_code", List.of("401"), "amount", List.of("99元")),
                IntentCategory.TECHNICAL_LOGIN, "technical", UrgencyLevel.MEDIUM, 0.9, "req-concurrency");
    }

    private AgentRequest singleRequest() {
        return new AgentRequest("登录失败提示401", "user", "conversation", "", List.of(), Map.of(),
                IntentCategory.TECHNICAL_LOGIN, "technical", UrgencyLevel.MEDIUM, 0.9, "req-single");
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
