package com.echomind.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryContextTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void newConversationCannotAcquireAnOrderFromProfileOrEpisodicRecall() {
        MemoryContext context = new MemoryContext(List.of(),
                List.of("另一会话订单 OTHER123 申请退款"),
                Map.of("preferences", List.of("OTHER123 要退到银行卡")), "");

        assertThat(context.toConversationPromptText(mapper)).isEmpty();
        assertThat(context.toPromptText(mapper)).contains("OTHER123", "用户画像", "相关历史");
        assertThat(context.userProfile()).isNotEmpty();
        assertThat(context.relevantHistory()).hasSize(1);
    }

    @Test
    void currentConversationKeepsItsSummaryAndMessageOrder() {
        MemoryContext context = new MemoryContext(List.of(
                new ConversationMessage(MessageRole.USER, "订单 CURRENT123 扣款299元", Instant.now(), Map.of()),
                new ConversationMessage(MessageRole.ASSISTANT, "请核对两笔交易记录", Instant.now(), Map.of())),
                List.of("OTHER123 退到银行卡"), Map.of("old_order", "OTHER123"), "当前会话正在核对重复扣款");

        assertThat(context.toConversationPromptText(mapper))
                .contains("当前会话正在核对重复扣款", "CURRENT123", "299元", "请核对两笔交易记录")
                .containsSubsequence("user:", "assistant:")
                .doesNotContain("OTHER123", "退到银行卡");
    }
}
