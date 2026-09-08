package com.echomind.agent;

import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerVerifierTest {

    @Test
    void doesNotEscalateConditionalManualInvestigation() {
        LlmGateway llm = (system, prompt, temperature, maxTokens) ->
                "{\"pass\":true,\"grounded\":true,\"need_escalation\":true,\"reason\":\"mentions manual review\"}";
        AnswerVerifier verifier = new AnswerVerifier(llm, new ObjectMapper());

        AnswerVerifier.VerificationResult result = verifier.verify(
                "登录提示401",
                "请先重新登录，若以上步骤无效，需要人工后台排查。",
                "401表示认证失败。"
        );

        assertThat(result.pass()).isTrue();
        assertThat(result.needEscalation()).isFalse();
    }

    @Test
    void escalatesWhenUserExplicitlyRequestsHumanSupport() {
        LlmGateway llm = (system, prompt, temperature, maxTokens) ->
                "{\"pass\":true,\"grounded\":true,\"need_escalation\":false,\"reason\":\"ok\"}";
        AnswerVerifier verifier = new AnswerVerifier(llm, new ObjectMapper());

        AnswerVerifier.VerificationResult result = verifier.verify("请转人工客服", "可以为您提供处理建议。", "");

        assertThat(result.needEscalation()).isTrue();
    }

    @Test
    void escalatesOnImmediateManualInstruction() {
        LlmGateway llm = (system, prompt, temperature, maxTokens) ->
                "{\"pass\":true,\"grounded\":true,\"need_escalation\":true,\"reason\":\"urgent\"}";
        AnswerVerifier verifier = new AnswerVerifier(llm, new ObjectMapper());

        AnswerVerifier.VerificationResult result = verifier.verify("账户被盗", "请立即联系人工客服冻结账户。", "");

        assertThat(result.needEscalation()).isTrue();
    }
}
