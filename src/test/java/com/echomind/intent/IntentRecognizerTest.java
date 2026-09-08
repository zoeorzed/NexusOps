package com.echomind.intent;

import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRecognizerTest {

    private final LlmGateway unavailableLlm = (system, prompt, temperature, maxTokens) -> "not-json";
    private final IntentRecognizer recognizer = new IntentRecognizer(unavailableLlm, new ObjectMapper());

    @Test
    void recognizesSpecificIntentAndExtractsBusinessEntitiesWithoutLlm() {
        IntentResult result = recognizer.recognize(
                "登录一直报401，订单号 #ABCD1234，今天扣了￥99.00",
                List.of()
        );

        assertThat(result.intent()).isEqualTo(IntentCategory.TECHNICAL_LOGIN);
        assertThat(result.intentGroup()).isEqualTo("technical");
        assertThat(result.entities().get("error_code")).contains("401");
        assertThat(result.entities().get("error_code")).doesNotContain("ABCD1234");
        assertThat(result.entities().get("order_id")).contains("ABCD1234");
        assertThat(result.entities().get("date")).contains("今天");
        assertThat(result.entities().get("amount")).contains("￥99.00");
    }

    @Test
    void doesNotTreatOrderIdAsErrorCodeInCompositeRequest() {
        IntentResult result = recognizer.recognize(
                "我登录失败并提示401，订单#A20260906001查不到，而且银行卡重复扣款299元",
                List.of()
        );

        assertThat(result.entities().get("order_id")).containsExactly("A20260906001");
        assertThat(result.entities().get("error_code")).contains("401").doesNotContain("A20260906001");
        assertThat(result.entities().get("amount")).contains("299元");
    }

    @Test
    void extractsOrderIdIntroducedByChineseCopula() {
        Map<String, List<String>> entities = recognizer.extractEntities("我的订单号是A20260906002，支付金额是199元。");

        assertThat(entities.get("order_id")).containsExactly("A20260906002");
        assertThat(entities.get("amount")).containsExactly("199元");
    }

    @Test
    void marksHumanHandoffAsHighUrgency() {
        IntentResult result = recognizer.recognize("请转人工客服", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.HUMAN_HANDOFF);
        assertThat(result.intentGroup()).isEqualTo("escalation");
        assertThat(result.urgency()).isEqualTo(UrgencyLevel.HIGH);
    }
}
