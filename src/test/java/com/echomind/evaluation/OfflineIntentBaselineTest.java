package com.echomind.evaluation;

import com.echomind.api.dto.EvalRunRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class OfflineIntentBaselineTest {
    @Test
    @SuppressWarnings("unchecked")
    void invokesProductionSpecificRuleWhenModelIsDeliberatelyUnavailable() {
        var report = OfflineIntentBaseline.evaluate(List.of(
                new EvalRunRequest.IntentCase("包裹物流怎么还未更新", "logistics")));
        assertThat(report.get("evaluation_mode")).isEqualTo("offline_production_intent_fallback");
        assertThat(report.get("total")).isEqualTo(1);
        var results = (List<Map<String, Object>>) report.get("results");
        assertThat(results.getFirst().get("predicted")).isEqualTo("logistics");
    }
}
