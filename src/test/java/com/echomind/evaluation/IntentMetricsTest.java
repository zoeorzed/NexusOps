package com.echomind.evaluation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class IntentMetricsTest {
    @Test
    void errorsRemainInDenominatorWithoutInventingATwentiethClass() {
        var truth = List.of("refund", "refund", "invoice");
        var predicted = List.of("refund", IntentMetrics.FAILED, "refund");
        assertThat(IntentMetrics.accuracy(predicted, truth)).isCloseTo(1.0 / 3, within(0.00001));
        assertThat(IntentMetrics.perClass(predicted, truth)).containsOnlyKeys("refund", "invoice");
        assertThat(IntentMetrics.perClass(predicted, truth).get("refund").get("support")).isEqualTo(2.0);
        assertThat(IntentMetrics.macroF1(predicted, truth)).isEqualTo(0.25);
    }

    @Test
    void emptyEvaluationIsUnavailableRatherThanZeroQuality() {
        assertThat(IntentMetrics.accuracy(List.of(), List.of())).isNull();
        assertThat(IntentMetrics.macroF1(List.of(), List.of())).isNull();
    }
}
