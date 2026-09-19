package com.echomind.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class AnswerVerifierFailureTest {
    @Test void unavailableJudgeDoesNotCertifyAnAnswerEvenWithContext() {
        AnswerVerifier verifier = new AnswerVerifier((s,p,t,m) -> {throw new IllegalStateException("offline");},new ObjectMapper());
        var result = verifier.verify("退款?","已退款","退款规则");
        assertThat(result.pass()).isFalse();
        assertThat(result.grounded()).isFalse();
    }
    @Test void invalidJudgeJsonIsUnverifiedAndPreservesExplicitHandoff() {
        AnswerVerifier verifier = new AnswerVerifier((s,p,t,m) -> "invalid",new ObjectMapper());
        var result = verifier.verify("转人工","请稍候","");
        assertThat(result.pass()).isFalse();
        assertThat(result.needEscalation()).isTrue();
    }
}
