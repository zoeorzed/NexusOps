package com.echomind.memory;

import com.echomind.intent.IntentRecognizer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationEntityResolverTest {

    private final ConversationEntityResolver resolver = new ConversationEntityResolver(new IntentRecognizer(null, null));

    @Test
    void fillsMissingCurrentEntitiesFromLatestUserHistory() {
        Map<String, List<String>> current = Map.of(
                "order_id", List.of(), "amount", List.of(), "error_code", List.of()
        );
        List<ConversationMessage> history = List.of(
                message(MessageRole.USER, "旧订单号是A20260906001，支付金额是99元"),
                message(MessageRole.ASSISTANT, "已记录订单A00000000000和500元"),
                message(MessageRole.USER, "我的订单号是A20260906002，支付金额是199元。")
        );

        Map<String, List<String>> resolved = resolver.resolve(current, history);

        assertThat(resolved.get("order_id")).containsExactly("A20260906002");
        assertThat(resolved.get("amount")).containsExactly("199元");
        assertThat(resolved.get("error_code")).isEmpty();
    }

    @Test
    void keepsEntitiesExtractedFromCurrentTurnAheadOfHistory() {
        Map<String, List<String>> current = Map.of(
                "order_id", List.of("A20260906003"), "amount", List.of("299元")
        );
        List<ConversationMessage> history = List.of(
                message(MessageRole.USER, "订单号是A20260906002，金额是199元")
        );

        Map<String, List<String>> resolved = resolver.resolve(current, history);

        assertThat(resolved.get("order_id")).containsExactly("A20260906003");
        assertThat(resolved.get("amount")).containsExactly("299元");
    }

    private ConversationMessage message(MessageRole role, String content) {
        return new ConversationMessage(role, content, Instant.now(), Map.of());
    }
}
