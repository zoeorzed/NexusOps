package com.echomind.agent;

import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;
import com.echomind.llm.LlmGateway;
import com.echomind.trace.RequestTraceStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRoutingRegressionTest {

    @Test
    void explicitCrashAndDuplicateDebitCallBothDomainsForEitherPrimaryIntent() {
        for (IntentCategory intent : List.of(IntentCategory.TECHNICAL_CRASH, IntentCategory.PAYMENT_ISSUE)) {
            for (String message : List.of("App 一打开就闪退，而且卡里多扣了一笔钱。",
                    "客户端崩溃，并且账户扣了两次。")) {
                CapturingAgents agents = new CapturingAgents();
                OrchestratorResult result = agents.run(message, intent);

                assertThat(result.agentTypes()).containsExactlyInAnyOrder(AgentType.TECHNICAL, AgentType.BILLING);
                assertThat(result.primaryAgent()).isEqualTo(intent == IntentCategory.TECHNICAL_CRASH
                        ? AgentType.TECHNICAL : AgentType.BILLING);
                assertThat(agents.callCount(AgentType.TECHNICAL)).isEqualTo(1);
                assertThat(agents.callCount(AgentType.BILLING)).isEqualTo(1);
                assertThat(agents.callCount(AgentType.GENERAL)).isZero();
            }
        }
    }

    @Test
    void compositePromptsContainOnlyTheirDomainProblem() {
        CapturingAgents agents = new CapturingAgents();

        agents.run("App 一打开就闪退而且卡里多扣了一笔钱", IntentCategory.PAYMENT_ISSUE);

        assertThat(agents.prompts.get(AgentType.TECHNICAL)).contains("App 一打开就闪退")
                .doesNotContain("卡里多扣了一笔钱");
        assertThat(agents.prompts.get(AgentType.BILLING)).contains("卡里多扣了一笔钱")
                .doesNotContain("App 一打开就闪退");
    }

    @Test
    void explicitSingleDomainProblemsDoNotCauseExtraCalls() {
        assertOnlyDomain("App 一打开就闪退", IntentCategory.TECHNICAL_CRASH, AgentType.TECHNICAL);
        assertOnlyDomain("卡里多扣了一笔钱", IntentCategory.PAYMENT_ISSUE, AgentType.BILLING);
        assertOnlyDomain("账号被盗，登录不进去", IntentCategory.ACCOUNT_SECURITY, AgentType.TECHNICAL);
        assertOnlyDomain("修改账户资料", IntentCategory.ACCOUNT, AgentType.BILLING);
    }

    @Test
    void obviousDenialsAndResolvedProblemsDoNotAddSupportingDomain() {
        assertOnlyDomain("App 闪退，但没有多扣款。", IntentCategory.TECHNICAL_CRASH, AgentType.TECHNICAL);
        assertOnlyDomain("App 闪退，重复扣款问题已经解决。", IntentCategory.TECHNICAL_CRASH, AgentType.TECHNICAL);
        assertOnlyDomain("App 闪退，多扣款问题已经解决。", IntentCategory.TECHNICAL_CRASH, AgentType.TECHNICAL);
        assertOnlyDomain("不是重复扣款，我只遇到 App 闪退。", IntentCategory.TECHNICAL_CRASH, AgentType.TECHNICAL);
        assertOnlyDomain("没有闪退，只是多扣了一笔钱。", IntentCategory.PAYMENT_ISSUE, AgentType.BILLING);
        assertOnlyDomain("登录失败已经解决，但重复扣款仍未处理。", IntentCategory.PAYMENT_ISSUE, AgentType.BILLING);
        assertOnlyDomain("账号被盗，但没有异常扣款。", IntentCategory.ACCOUNT_SECURITY, AgentType.TECHNICAL);
    }

    @Test
    void genericBillingWordAloneDoesNotBecomeStrongProblemSignal() {
        assertOnlyDomain("App 闪退，我是在支付页面遇到的。", IntentCategory.TECHNICAL_CRASH, AgentType.TECHNICAL);
    }

    @Test
    void securityAndAbnormalDebitRemainCompositeForEitherPrimaryIntent() {
        for (IntentCategory intent : List.of(IntentCategory.ACCOUNT_SECURITY, IntentCategory.PAYMENT_ISSUE)) {
            CapturingAgents agents = new CapturingAgents();

            OrchestratorResult result = agents.run("账号被盗且登录不进去，而且发现异常扣款。", intent);

            assertThat(result.agentTypes()).containsExactlyInAnyOrder(AgentType.TECHNICAL, AgentType.BILLING);
            assertThat(agents.prompts.get(AgentType.TECHNICAL)).contains("账号被盗且登录不进去", "账户保护、登录恢复")
                    .doesNotContain("发现异常扣款");
            assertThat(agents.prompts.get(AgentType.BILLING)).contains("发现异常扣款")
                    .doesNotContain("账号被盗且登录不进去");
        }
    }

    private void assertOnlyDomain(String message, IntentCategory intent, AgentType expected) {
        CapturingAgents agents = new CapturingAgents();
        OrchestratorResult result = agents.run(message, intent);
        assertThat(result.agentTypes()).as(message).containsExactly(expected);
        assertThat(result.supportingAgents()).as(message).isEmpty();
        assertThat(agents.calls.values().stream().mapToInt(AtomicInteger::get).sum()).as(message).isEqualTo(1);
    }

    private static class CapturingAgents {
        private final Map<AgentType, String> prompts = new ConcurrentHashMap<>();
        private final Map<AgentType, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final AgentOrchestrator orchestrator = new AgentOrchestrator(null, Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(capture(AgentType.GENERAL), null)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(capture(AgentType.TECHNICAL), null)),
                AgentType.BILLING, List.of(new BillingAgent(capture(AgentType.BILLING), null))
        ), new RequestTraceStore());

        private LlmGateway capture(AgentType type) {
            return (system, prompt, temperature, maxTokens) -> {
                calls.computeIfAbsent(type, ignored -> new AtomicInteger()).incrementAndGet();
                prompts.put(type, prompt);
                return "确定性测试回复";
            };
        }

        private OrchestratorResult run(String message, IntentCategory intent) {
            return orchestrator.run(new AgentRequest(message, "regression-user", "regression-conversation", "",
                    List.of(), Map.of(), intent, null, UrgencyLevel.MEDIUM, 0.9, "regression-request"));
        }

        private int callCount(AgentType type) {
            return calls.getOrDefault(type, new AtomicInteger()).get();
        }
    }
}
