package com.echomind.api;

import com.echomind.agent.*;
import com.echomind.api.dto.ChatRequest;
import com.echomind.intent.*;
import com.echomind.memory.*;
import com.echomind.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatMemoryFlowTest {
    @org.junit.jupiter.api.Test void capabilityQuestionsDoNotRetrieveUnrelatedBusinessPolicies() {
        assertThat(EchoMindController.isMemoryCapabilityOnly("我没告诉你单号金额，你完全没有会话记忆吗？")).isTrue();
        assertThat(EchoMindController.isMemoryCapabilityOnly("你有会话记忆吗？另外退款多久到账？")).isFalse();
        assertThat(EchoMindController.isMemoryCapabilityOnly("这笔订单退款多久到账？")).isFalse();
    }
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void historicalOrderReachesAgentAndVerificationOutcomeRemainsObservable(boolean verificationPassed) throws Exception {
        var intent = mock(IntentRecognizer.class);
        var memory = mock(MemoryManager.class);
        var orchestrator = mock(AgentOrchestrator.class);
        var tools = mock(KnowledgeToolManager.class);
        var verifier = mock(AnswerVerifier.class);
        var history = List.of(new ConversationMessage(MessageRole.USER,"订单#A20260914001重复扣款299元",Instant.now(),Map.of()));
        when(memory.getContext(anyString(),anyString(),anyString())).thenReturn(new MemoryContext(history,
                List.of("另一会话的订单 OLD999 要求退款到银行卡"),
                Map.of("preferences", List.of("此前另一笔订单希望退到银行卡")), "当前会话摘要"));
        when(intent.recognize(anyString(),anyList())).thenReturn(new IntentResult(IntentCategory.REFUND,.9,UrgencyLevel.MEDIUM,
                "billing",Map.of(),"test",0,Map.of()));
        when(intent.extractEntities(anyString())).thenReturn(Map.of("order_id",List.of("A20260914001"),"amount",List.of("299元")));
        when(tools.searchWithRewrite(anyString(),anyInt())).thenReturn(new ToolResult<>(true,List.of(),"knowledge_search",null,false,0,false));
        when(orchestrator.run(any(AgentRequest.class),anyList())).thenReturn(new OrchestratorResult(
                "req","请核实订单",AgentType.BILLING,IntentCategory.REFUND,false,0,List.of(AgentType.BILLING),
                AgentType.BILLING,List.of(),List.of(),List.of(),"test",.9));
        String reason = verificationPassed ? "用户信息与上下文一致" : "退款到账时限缺少依据";
        String finalAnswer = verificationPassed ? "审核后的回答" : "无法确认";
        when(verifier.review(anyString(),anyString(),anyString())).thenReturn(new AnswerVerifier.ReviewedAnswer(finalAnswer,new AnswerVerifier.VerificationResult(verificationPassed,verificationPassed,false,reason)));
        var controller = new EchoMindController(orchestrator,intent,memory,new ConversationEntityResolver(intent),tools,
                null,verifier,null,null,null,new ObjectMapper(),null);
        var response = controller.chat(new ChatRequest("那这笔订单怎么退款？","demo","c"));
        var captured = ArgumentCaptor.forClass(AgentRequest.class);
        verify(orchestrator).run(captured.capture(),anyList());
        assertThat(captured.getValue().entities()).containsEntry("order_id",List.of("A20260914001"));
        assertThat(captured.getValue().context()).contains("A20260914001", "当前会话摘要")
                .doesNotContain("OLD999", "退到银行卡", "用户画像", "相关历史");
        assertThat(response.response()).isEqualTo(finalAnswer);
        verify(memory).addMessage("demo","c",MessageRole.ASSISTANT,finalAnswer);
        verify(memory,never()).addMessage("demo","c",MessageRole.ASSISTANT,"请核实订单");
        assertThat(response.currentEntities()).isEmpty();
        assertThat(response.resolvedEntities()).containsEntry("order_id",List.of("A20260914001"));
        assertThat(response.verified()).isEqualTo(verificationPassed);
        assertThat(response.grounded()).isEqualTo(verificationPassed);
        assertThat(response.verificationReason()).isEqualTo(reason);
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));
        assertThat(json.path("verification_reason").asText()).isEqualTo(reason);
        verify(memory).addMessage("demo","c",MessageRole.USER,"那这笔订单怎么退款？");
    }
}
