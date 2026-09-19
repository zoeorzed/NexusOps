package com.echomind.api;

import com.echomind.agent.*;
import com.echomind.api.dto.ChatRequest;
import com.echomind.intent.*;
import com.echomind.memory.*;
import com.echomind.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatMemoryFlowTest {
    @Test void historicalOrderReachesAgentBeforeGenerationAndCurrentEntitiesStaySeparate() {
        var intent = mock(IntentRecognizer.class);
        var memory = mock(MemoryManager.class);
        var orchestrator = mock(AgentOrchestrator.class);
        var tools = mock(KnowledgeToolManager.class);
        var verifier = mock(AnswerVerifier.class);
        var history = List.of(new ConversationMessage(MessageRole.USER,"订单#A20260914001重复扣款299元",Instant.now(),Map.of()));
        when(memory.getContext(anyString(),anyString(),anyString())).thenReturn(new MemoryContext(history,List.of(),Map.of(),""));
        when(intent.recognize(anyString(),anyList())).thenReturn(new IntentResult(IntentCategory.REFUND,.9,UrgencyLevel.MEDIUM,
                "billing",Map.of(),"test",0,Map.of()));
        when(intent.extractEntities(anyString())).thenReturn(Map.of("order_id",List.of("A20260914001"),"amount",List.of("299元")));
        when(tools.searchWithRewrite(anyString(),anyInt())).thenReturn(new ToolResult<>(true,List.of(),"knowledge_search",null,false,0,false));
        when(orchestrator.run(any(AgentRequest.class),anyList())).thenReturn(new OrchestratorResult(
                "req","请核实订单",AgentType.BILLING,IntentCategory.REFUND,false,0,List.of(AgentType.BILLING),
                AgentType.BILLING,List.of(),List.of(),List.of(),"test",.9));
        when(verifier.verify(anyString(),anyString(),anyString())).thenReturn(new AnswerVerifier.VerificationResult(true,true,false,"test"));
        var controller = new EchoMindController(orchestrator,intent,memory,new ConversationEntityResolver(intent),tools,
                null,verifier,null,null,null,new ObjectMapper(),null);
        var response = controller.chat(new ChatRequest("那这笔订单怎么退款？","demo","c"));
        var captured = ArgumentCaptor.forClass(AgentRequest.class);
        verify(orchestrator).run(captured.capture(),anyList());
        assertThat(captured.getValue().entities()).containsEntry("order_id",List.of("A20260914001"));
        assertThat(response.currentEntities()).isEmpty();
        assertThat(response.resolvedEntities()).containsEntry("order_id",List.of("A20260914001"));
        verify(memory).addMessage("demo","c",MessageRole.USER,"那这笔订单怎么退款？");
    }
}
