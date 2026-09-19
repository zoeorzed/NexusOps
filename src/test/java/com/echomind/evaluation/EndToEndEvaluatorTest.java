package com.echomind.evaluation;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;

class EndToEndEvaluatorTest {

    @TempDir
    Path tempDir;

    @Test
    void savesBaselineOnlyWhenExplicitlyRequested() {
        Path baseline = tempDir.resolve("baseline.json");
        EchoMindProperties properties = new EchoMindProperties();
        properties.getEval().setBaselinePath(baseline.toString());
        EndToEndEvaluator evaluator = new EndToEndEvaluator(
                mock(IntentRecognizer.class),
                mock(AgentOrchestrator.class),
                mock(LLMJudge.class),
                new ObjectMapper(),
                properties
        );

        evaluator.run(new EvalRunRequest(List.of(), List.of(), false));
        assertThat(baseline).doesNotExist();

        evaluator.run(new EvalRunRequest(List.of(), List.of(), true));
        assertThat(baseline).exists();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedPredictionsAndInvalidJudgesStayInPassDenominatorButNotQualityAverage() {
        IntentRecognizer recognizer = mock(IntentRecognizer.class);
        when(recognizer.recognize(anyString(), isNull()))
                .thenReturn(intent(IntentCategory.REFUND, "LLM recognition failed"))
                .thenThrow(new IllegalStateException("broken prediction"));
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.run(any(AgentRequest.class)))
                .thenReturn(response(), response()).thenThrow(new IllegalStateException("broken orchestration"));
        LLMJudge judge = mock(LLMJudge.class);
        when(judge.judge(anyString(), anyString(), anyString()))
                .thenReturn(new QualityScores(1, 1, 1, 1, false, null))
                .thenReturn(new QualityScores(1, 1, 1, 1, true, "failed even with high placeholder scores"));
        var evaluator = evaluator(recognizer, orchestrator, judge);
        var report = evaluator.run(new EvalRunRequest(List.of(
                new EvalRunRequest.IntentCase("case one", "refund"),
                new EvalRunRequest.IntentCase("case two", "invoice")), List.of(
                new EvalRunRequest.DialogCase("first", null, null, null),
                new EvalRunRequest.DialogCase("second", null, null, null),
                new EvalRunRequest.DialogCase("third", null, null, null)), false));
        assertThat(report.get("total")).isEqualTo(5);
        assertThat(report.get("passed")).isEqualTo(2L);
        assertThat(report.get("pass_rate")).isEqualTo(0.4);
        assertThat(report.get("intent_failure_count")).isEqualTo(1L);
        assertThat(report.get("intent_llm_failure_count")).isEqualTo(1L);
        assertThat(report.get("dialog_valid_count")).isEqualTo(1L);
        assertThat(report.get("dialog_invalid_count")).isEqualTo(2L);
        assertThat(report.get("judge_failure_count")).isEqualTo(1L);
        assertThat(report.get("dialog_execution_failure_count")).isEqualTo(1L);
        Map<String, Object> averages = (Map<String, Object>) report.get("avg_scores");
        assertThat(averages).containsEntry("intent_accuracy", 0.5)
                .containsEntry("macro_f1", 0.5).containsEntry("dialog_overall", 1.0);
        List<Map<String, Object>> results = (List<Map<String, Object>>) report.get("results");
        assertThat((Map<?, ?>) results.get(3).get("scores")).isEmpty();
        assertThat((Map<?, ?>) results.get(4).get("scores")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void allInvalidDialogsHaveNoQualityScore() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.run(any(AgentRequest.class))).thenThrow(new IllegalStateException());
        var report = evaluator(mock(IntentRecognizer.class), orchestrator, mock(LLMJudge.class))
                .run(new EvalRunRequest(List.of(), List.of(new EvalRunRequest.DialogCase("broken", null, null, null)), false));
        assertThat((Map<String, Object>) report.get("avg_scores")).containsEntry("dialog_overall", null);
        assertThat(report.get("dialog_valid_rate")).isEqualTo(0.0);
        assertThat(report.get("pass_rate")).isEqualTo(0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparesOnlyMatchingDatasetModeAndMetricVersion() throws Exception {
        IntentRecognizer recognizer = mock(IntentRecognizer.class);
        when(recognizer.recognize(anyString(), isNull())).thenReturn(intent(IntentCategory.REFUND, "model"));
        var evaluator = evaluator(recognizer, mock(AgentOrchestrator.class), mock(LLMJudge.class));
        var cases = List.of(new EvalRunRequest.IntentCase("same input", "refund"));
        evaluator.run(new EvalRunRequest(cases, List.of(), true));
        when(recognizer.recognize(anyString(), isNull())).thenReturn(intent(IntentCategory.INVOICE, "model"));
        var matching = evaluator.run(new EvalRunRequest(cases, List.of(), false));
        assertThat(matching.get("baseline_comparison")).isEqualTo("matched_dataset_mode_metrics");
        assertThat((List<String>) matching.get("regressions")).hasSize(2);

        var different = evaluator.run(new EvalRunRequest(
                List.of(new EvalRunRequest.IntentCase("different input", "refund")), List.of(), false));
        assertThat(different.get("baseline_comparison")).isEqualTo("incompatible_dataset_mode_or_metrics");
        assertThat((List<?>) different.get("regressions")).isEmpty();

        ObjectMapper mapper = new ObjectMapper();
        Path baseline = tempDir.resolve("baseline.json");
        Map<String, Object> saved = mapper.readValue(baseline.toFile(), Map.class);
        for (String field : List.of("evaluation_mode", "metrics_version")) {
            Map<String, Object> changed = new LinkedHashMap<>(saved);
            changed.put(field, "incompatible");
            mapper.writeValue(baseline.toFile(), changed);
            var report = evaluator.run(new EvalRunRequest(cases, List.of(), false));
            assertThat(report.get("baseline_comparison")).isEqualTo("incompatible_dataset_mode_or_metrics");
            assertThat((List<?>) report.get("regressions")).isEmpty();
        }
    }

    private EndToEndEvaluator evaluator(IntentRecognizer recognizer, AgentOrchestrator orchestrator, LLMJudge judge) {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getEval().setBaselinePath(tempDir.resolve("baseline.json").toString());
        return new EndToEndEvaluator(recognizer, orchestrator, judge, new ObjectMapper(), properties);
    }

    private IntentResult intent(IntentCategory category, String reasoning) {
        return new IntentResult(category, 1.0, UrgencyLevel.LOW, category.name(), Map.of(), reasoning, 0, Map.of());
    }

    private OrchestratorResult response() {
        return new OrchestratorResult("eval", "answer", AgentType.GENERAL, IntentCategory.QUERY, false, 1,
                List.of(AgentType.GENERAL), AgentType.GENERAL, List.of(), List.of(), List.of(), "test", 1.0);
    }
}
