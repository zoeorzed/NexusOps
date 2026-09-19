package com.echomind.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LLMJudgeTest {
    @Test
    void incompleteOutOfRangeAndNonFiniteScoresAreFailures() {
        for (String raw : new String[] {
                "{}",
                "{\"relevance\":1,\"accuracy\":1,\"completeness\":1,\"helpfulness\":2}",
                "{\"relevance\":\"NaN\",\"accuracy\":1,\"completeness\":1,\"helpfulness\":1}",
                "not json"
        }) {
            var judge = new LLMJudge((system, user, temperature, tokens) -> raw, new ObjectMapper());
            var score = judge.judge("question", "response", "");
            assertThat(score.judgeFailed()).isTrue();
        }
    }

    @Test
    void validScoresArePreserved() {
        var judge = new LLMJudge((system, user, temperature, tokens) ->
                "{\"relevance\":1,\"accuracy\":0.8,\"completeness\":0.7,\"helpfulness\":0.9}", new ObjectMapper());
        var score = judge.judge("question", "response", "");
        assertThat(score.judgeFailed()).isFalse();
        assertThat(score.accuracy()).isEqualTo(0.8);
    }
}
