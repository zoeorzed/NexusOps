package com.echomind.tool;

import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.knowledge.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeToolManagerTest {
    @Test void repeatedRecallFailuresOpenCircuitInsteadOfReportingSuccess() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.search(anyString(), anyInt())).thenThrow(new IllegalStateException("test"));
        KnowledgeToolManager tools = new KnowledgeToolManager(kb, (s,p,t,m) -> "[]", new ObjectMapper());
        for (int i=0;i<5;i++) assertThat(tools.searchWithRewrite("退款",3).success()).isFalse();
        assertThat(tools.searchWithRewrite("退款",3).error()).isEqualTo("circuit open");
        verify(kb, times(5)).search(anyString(), anyInt());
    }
    @Test void skippedAndFailedRerankAreNotReportedAsModelReranking() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        SearchResult a = new SearchResult("a","a","退款政策",0.9,0,Map.of());
        SearchResult b = new SearchResult("b","b","登录政策",0.5,0,Map.of());
        when(kb.search(anyString(),anyInt())).thenReturn(List.of(a,b));
        KnowledgeToolManager tools = new KnowledgeToolManager(kb, (s,p,t,m) -> "invalid-json", new ObjectMapper());
        assertThat(tools.searchWithRewrite("退款",3).reranked()).isFalse();
        ToolResult<List<SearchResult>> failed = tools.searchWithRewrite("退款",1);
        assertThat(failed.success()).isTrue();
        assertThat(failed.reranked()).isFalse();
        assertThat(failed.error()).contains("rerank unavailable");
        assertThat(failed.data()).containsExactly(a);
    }
}
