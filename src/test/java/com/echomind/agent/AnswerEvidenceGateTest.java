package com.echomind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class AnswerEvidenceGateTest {
    private String audit(String statement, String kind, String verdict, String evidence, String contradictions) {
        return "{\"pass\":true,\"grounded\":true,\"need_escalation\":false,\"reason\":\"ok\",\"claims\":[{\"statement_id\":\"A0\",\"kind\":\""+kind+"\",\"verdict\":\""+verdict+"\",\"source_ids\":[\""+evidence+"\"]}],\"contradictions\":"+contradictions+"}";
    }
    @Test void unsupportedFactOverridesPassingModelFlag() {
        var verifier=new AnswerVerifier((s,p,t,m)->audit("不是。","FACT","UNKNOWN","","[]"),new ObjectMapper());
        var result=verifier.verify("是否可行？","不是。无法确认。","仅支持邮箱重置");
        assertThat(result.pass()).isFalse();assertThat(result.grounded()).isFalse();
        assertThat(result.reason()).contains("不是。");
    }
    @Test void contradictionOverridesPassingModelFlag() {
        var verifier=new AnswerVerifier((s,p,t,m)->audit("不是。","FACT","SUPPORTED","不是。","[\"断言与未知矛盾\"]"),new ObjectMapper());
        assertThat(verifier.verify("?","不是。无法确认。","不是。").pass()).isFalse();
    }
    @Test void fabricatedQuotationCannotSupportFact() {
        var verifier=new AnswerVerifier((s,p,t,m)->audit("可以重置","FACT","SUPPORTED","MISSING","[]"),new ObjectMapper());
        assertThat(verifier.verify("?","可以重置","仅有邮箱方式").reason()).contains("invalid source reference");
    }
    @Test void supportedFactAndHonestUncertaintyCanPass() {
        var valid=new AnswerVerifier((s,p,t,m)->audit("可用邮箱重置","FACT","SUPPORTED","C0","[]"),new ObjectMapper());
        assertThat(valid.verify("?","可用邮箱重置","可用邮箱重置").pass()).isTrue();
        var unknown=new AnswerVerifier((s,p,t,m)->audit("无法确认入口","UNCERTAINTY","UNKNOWN","","[]"),new ObjectMapper());
        assertThat(unknown.verify("在哪","无法确认入口","可用邮箱重置").pass()).isTrue();
    }
    @Test void missingAuditOrInventedStatementFailsClosed() {
        for(String raw:List.of("{\"pass\":true,\"grounded\":true}",audit("原文没有这句话","FACT","SUPPORTED","C0","[]").replace("A0", "A99"))) {
            var verifier=new AnswerVerifier((s,p,t,m)->raw,new ObjectMapper());
            assertThat(verifier.verify("?","原回答","依据").pass()).isFalse();
        }
    }
    @Test void missingOrDuplicatedSentenceAuditCannotPass() {
        String raw = audit("", "FACT", "SUPPORTED", "C0", "[]");
        var verifier = new AnswerVerifier((s,p,t,m)->raw, new ObjectMapper());
        assertThat(verifier.verify("?", "第一句。第二句。", "依据").reason()).contains("incomplete evidence audit");
        String duplicate = raw.replace("}],", "},{\"statement_id\":\"A0\",\"kind\":\"FACT\",\"verdict\":\"SUPPORTED\",\"source_ids\":[\"C0\"]}],");
        var repeated = new AnswerVerifier((s,p,t,m)->duplicate, new ObjectMapper());
        assertThat(repeated.verify("?", "第一句。", "依据").pass()).isFalse();
    }
    @Test void sentenceIdsAvoidCopyingMarkdownOrWhitespace() {
        var verifier = new AnswerVerifier((s,p,t,m)->{
            assertThat(p).contains("[待审句子]", "A0", "**原账户**");
            return audit("", "FACT", "SUPPORTED", "C0", "[]");
        }, new ObjectMapper());
        assertThat(verifier.verify("?", "  退回 **原账户**。\n", "退回原账户").pass()).isTrue();
    }
    @Test void repairIsReverifiedBeforeReturning() {
        var replies=new ArrayDeque<>(List.of(audit("不是","FACT","UNKNOWN","","[]"),"无法确认入口",audit("无法确认入口","UNCERTAINTY","UNKNOWN","","[]")));
        var verifier=new AnswerVerifier((s,p,t,m)->replies.removeFirst(),new ObjectMapper());
        var result=verifier.review("在哪","不是","仅支持邮箱");
        assertThat(result.answer()).isEqualTo("无法确认入口");assertThat(result.verification().pass()).isTrue();
        assertThat(result.verification().reason()).contains("draft rejected", "revision");assertThat(replies).isEmpty();
    }
    @Test void failedRevisionIsWithheldWithNoFurtherRetry() {
        var calls=new AtomicInteger();
        var replies=new ArrayDeque<>(List.of(audit("不是","FACT","UNKNOWN","","[]"),"肯定可以",audit("肯定可以","FACT","UNKNOWN","","[]")));
        var verifier=new AnswerVerifier((s,p,t,m)->{calls.incrementAndGet();return replies.removeFirst();},new ObjectMapper());
        var result=verifier.review("在哪","不是","仅支持邮箱");
        assertThat(calls.get()).isEqualTo(3);assertThat(result.verification().pass()).isFalse();
        assertThat(result.answer()).doesNotContain("不是","肯定可以");assertThat(result.verification().reason()).contains("withheld");
    }
    @Test void unavailableVerifierDoesNotReleaseDraftOrAttemptRepair() {
        var calls=new AtomicInteger();
        var verifier=new AnswerVerifier((s,p,t,m)->{calls.incrementAndGet();throw new IllegalStateException();},new ObjectMapper());
        var result=verifier.review("?","已执行退款","");
        assertThat(calls.get()).isEqualTo(1);assertThat(result.answer()).doesNotContain("已执行退款");
        assertThat(result.verification().pass()).isFalse();
    }
}
