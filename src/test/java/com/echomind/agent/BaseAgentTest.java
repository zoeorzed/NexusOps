package com.echomind.agent;

import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;
import com.echomind.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaseAgentTest {

    @Test
    void doesNotTreatNegatedOrConditionalHumanHandoffAsEscalated() {
        LlmGateway llm = (system, prompt, temperature, maxTokens) ->
                "若仍失败，需要人工后台排查；当前对话不代表已转人工。";
        AgentResponse response = new TechnicalAgent(llm, null).handle(request());

        assertThat(response.escalate()).isFalse();
    }

    @Test
    void treatsImmediateHumanInstructionAsEscalated() {
        LlmGateway llm = (system, prompt, temperature, maxTokens) ->
                "涉及账户安全，请立即联系人工客服。";
        AgentResponse response = new TechnicalAgent(llm, null).handle(request());

        assertThat(response.escalate()).isTrue();
    }

    @Test
    void normalizesDuplicatedMarkdownBoldMarkers() {
        LlmGateway llm = (system, prompt, temperature, maxTokens) -> "**第二步****：重新登录";
        AgentResponse response = new TechnicalAgent(llm, null).handle(request());

        assertThat(response.content()).isEqualTo("**第二步**：重新登录");
    }

    private AgentRequest request() {
        return new AgentRequest(
                "登录提示401", "user", "conversation", "", List.of(), Map.of("error_code", List.of("401")),
                IntentCategory.TECHNICAL_LOGIN, "technical", UrgencyLevel.MEDIUM, 0.8, "request"
        );
    }
}
