package com.echomind.agent;

import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;
import com.echomind.llm.LlmGateway;
import com.echomind.trace.RequestTraceStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorTest {

    private final LlmGateway deterministicLlm = (system, prompt, temperature, maxTokens) -> "处理完成";

    @Test
    void routesCompositeRequestToPrimaryAndSupportingAgentsAndRecordsTrace() {
        RequestTraceStore traceStore = new RequestTraceStore();
        Map<AgentType, List<BaseAgent>> pool = Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(deterministicLlm, null)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(deterministicLlm, null)),
                AgentType.BILLING, List.of(new BillingAgent(deterministicLlm, null))
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(null, pool, traceStore);
        AgentRequest request = new AgentRequest(
                "登录报401，而且重复扣款、支付失败，需要退款",
                "user-1",
                "conversation-1",
                "",
                List.of(),
                Map.of("error_code", List.of("401"), "amount", List.of("99元")),
                IntentCategory.TECHNICAL_LOGIN,
                "technical",
                UrgencyLevel.HIGH,
                0.9,
                "req-1"
        );

        OrchestratorResult result = orchestrator.run(request);

        assertThat(result.primaryAgent()).isEqualTo(AgentType.TECHNICAL);
        assertThat(result.supportingAgents()).contains(AgentType.BILLING);
        assertThat(result.agentTypes()).contains(AgentType.TECHNICAL, AgentType.BILLING);
        assertThat(result.routingReason()).contains("primary=technical", "supporting=billing");
        assertThat(traceStore.find("req-1")).isPresent();
        assertThat(traceStore.find("req-1").orElseThrow().supportingAgents()).contains("billing");
    }

    @Test
    void routesRealCompositeLoginAndPaymentRequestToTechnicalAndBillingAgents() {
        RequestTraceStore traceStore = new RequestTraceStore();
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        LlmGateway scopedLlm = (system, prompt, temperature, maxTokens) -> {
            prompts.add(system + "\n" + prompt);
            return "处理完成";
        };
        Map<AgentType, List<BaseAgent>> pool = Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(scopedLlm, null)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(scopedLlm, null)),
                AgentType.BILLING, List.of(new BillingAgent(scopedLlm, null))
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(null, pool, traceStore);
        AgentRequest request = new AgentRequest(
                "我登录失败并提示401，订单#A20260906001查不到，而且银行卡重复扣款299元",
                "demo-user-003",
                "demo-conv-003",
                "",
                List.of(),
                Map.of(
                        "order_id", List.of("A20260906001"),
                        "error_code", List.of("401"),
                        "amount", List.of("299元")
                ),
                IntentCategory.TECHNICAL_LOGIN,
                "technical",
                UrgencyLevel.HIGH,
                0.6814,
                "req-real-composite"
        );

        OrchestratorResult result = orchestrator.run(request);

        assertThat(result.primaryAgent()).isEqualTo(AgentType.TECHNICAL);
        assertThat(result.supportingAgents()).containsExactly(AgentType.BILLING);
        assertThat(result.agentTypes()).containsExactly(AgentType.TECHNICAL, AgentType.BILLING);
        assertThat(result.routingReason()).contains("primary=technical", "supporting=billing");
        assertThat(traceStore.find("req-real-composite").orElseThrow().supportingAgents())
                .containsExactly("billing");
        assertThat(prompts).anySatisfy(prompt -> {
            assertThat(prompt).contains("[技术子任务]", "当前就是 Technical Agent", "不要回答扣款", "系统没有真实工单写入能力");
            assertThat(prompt).doesNotContain("amount=[299元]", "重复扣款299元");
        });
        assertThat(prompts).anySatisfy(prompt -> {
            assertThat(prompt).contains("[账务子任务]", "当前就是 Billing Agent", "核验、退款申请审核和审核通过后到账", "不得将审核时限当作到账承诺");
            assertThat(prompt).doesNotContain("error_code=[401]", "登录失败", "提示401", "查不到");
        });
    }

    @Test
    void doesNotLetGeneralAgentLeadWhenSpecificDomainsAreDetected() {
        RequestTraceStore traceStore = new RequestTraceStore();
        Map<AgentType, List<BaseAgent>> pool = Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(deterministicLlm, null)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(deterministicLlm, null)),
                AgentType.BILLING, List.of(new BillingAgent(deterministicLlm, null))
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(null, pool, traceStore);
        AgentRequest request = new AgentRequest(
                "我登录失败并提示401，订单#A20260906001查不到，而且银行卡重复扣款299元，请帮我同时处理。",
                "user-general-intent", "conv-general-intent", "", List.of(),
                Map.of("order_id", List.of("A20260906001"), "error_code", List.of("401"), "amount", List.of("299元")),
                IntentCategory.REQUEST, "general", UrgencyLevel.MEDIUM, 0.8, "req-general-intent"
        );

        OrchestratorResult result = orchestrator.run(request);

        assertThat(result.primaryAgent()).isEqualTo(AgentType.TECHNICAL);
        assertThat(result.supportingAgents()).containsExactly(AgentType.BILLING);
        assertThat(result.agentTypes()).containsExactly(AgentType.TECHNICAL, AgentType.BILLING);
    }

    @Test
    void keepsSingleDomainTechnicalRequestOnTechnicalAgentOnly() {
        RequestTraceStore traceStore = new RequestTraceStore();
        Map<AgentType, List<BaseAgent>> pool = Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(deterministicLlm, null)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(deterministicLlm, null)),
                AgentType.BILLING, List.of(new BillingAgent(deterministicLlm, null))
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(null, pool, traceStore);
        AgentRequest request = new AgentRequest(
                "登录失败并提示401",
                "user-technical",
                "conv-technical",
                "",
                List.of(),
                Map.of("error_code", List.of("401")),
                IntentCategory.TECHNICAL_LOGIN,
                "technical",
                UrgencyLevel.MEDIUM,
                0.8,
                "req-technical-only"
        );

        OrchestratorResult result = orchestrator.run(request);

        assertThat(result.primaryAgent()).isEqualTo(AgentType.TECHNICAL);
        assertThat(result.supportingAgents()).isEmpty();
        assertThat(result.agentTypes()).containsExactly(AgentType.TECHNICAL);
    }

    @Test
    void removesCrossAgentNoticeFromLastDisplayedAgentOnly() {
        LlmGateway llm = (system, prompt, temperature, maxTokens) -> {
            if (prompt.contains("[账务子任务]")) {
                return "账务答复。\n\n技术问题将由下一个协同 Technical Agent 独立处理。";
            }
            return "技术答复。\n\n### 当前处理范围说明\n\n账务问题由协同的 Billing Agent 独立处理，此处不展开。";
        };
        Map<AgentType, List<BaseAgent>> pool = Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(llm, null)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(llm, null)),
                AgentType.BILLING, List.of(new BillingAgent(llm, null))
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(null, pool, new RequestTraceStore());
        AgentRequest request = new AgentRequest(
                "登录提示401，而且重复扣款299元", "user", "conversation", "", List.of(),
                Map.of("error_code", List.of("401"), "amount", List.of("299元")),
                IntentCategory.PAYMENT_ISSUE, "billing", UrgencyLevel.MEDIUM, 0.8, "req-ending"
        );

        OrchestratorResult result = orchestrator.run(request);

        assertThat(result.agentTypes()).containsExactly(AgentType.BILLING, AgentType.TECHNICAL);
        assertThat(result.response()).contains("技术问题将由下一个协同 Technical Agent 独立处理。");
        assertThat(result.response()).doesNotContain("当前处理范围说明", "账务问题由协同的 Billing Agent");
        assertThat(result.response()).endsWith("技术答复。");
    }
}
