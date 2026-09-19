package com.echomind.evaluation;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

@Service
public class EndToEndEvaluator {

    private final IntentRecognizer intentRecognizer;
    private final AgentOrchestrator orchestrator;
    private final LLMJudge judge;
    private final ObjectMapper objectMapper;
    private final EchoMindProperties properties;

    public EndToEndEvaluator(IntentRecognizer intentRecognizer, AgentOrchestrator orchestrator, LLMJudge judge, ObjectMapper objectMapper, EchoMindProperties properties) {
        this.intentRecognizer = intentRecognizer;
        this.orchestrator = orchestrator;
        this.judge = judge;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Map<String, Object> run(EvalRunRequest request) {
        List<EvalRunRequest.IntentCase> intentCases = request != null && request.intentCases() != null
                ? request.intentCases()
                : defaultIntentCases();
        List<EvalRunRequest.DialogCase> dialogCases = request != null && request.dialogCases() != null
                ? request.dialogCases()
                : defaultDialogCases();
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> predictions = new ArrayList<>();
        List<String> groundTruth = new ArrayList<>();
        long intentCorrect = 0;
        long intentFailureCount = 0;
        long intentLlmFailureCount = 0;
        for (EvalRunRequest.IntentCase c : intentCases) {
            String predicted;
            String error = "";
            boolean llmRecognitionFailed = false;
            try {
                IntentResult result = intentRecognizer.recognize(c.message(), null);
                predicted = result.intent().name().toLowerCase(Locale.ROOT);
                llmRecognitionFailed = "LLM recognition failed".equals(result.reasoning());
                if (llmRecognitionFailed) intentLlmFailureCount++;
            } catch (Exception ex) {
                predicted = IntentMetrics.FAILED;
                error = ex.getClass().getSimpleName();
                intentFailureCount++;
            }
            predictions.add(predicted);
            groundTruth.add(c.expectedIntent());
            boolean passed = predicted.equals(c.expectedIntent());
            if (passed) {
                intentCorrect++;
            }
            results.add(Map.of(
                    "test_id", "intent_" + results.size(),
                    "passed", passed,
                    "scores", Map.of("accuracy", passed ? 1.0 : 0.0),
                    "metadata", Map.of("message", c.message(), "expected", c.expectedIntent(), "predicted", predicted,
                            "prediction_failed", !error.isEmpty(), "error", error,
                            "llm_recognition_failed", llmRecognitionFailed)
            ));
        }
        for (EvalRunRequest.DialogCase c : dialogCases) {
            List<String> turns = c.turns() != null && !c.turns().isEmpty() ? c.turns() : List.of(c.question());
            List<Map<String, String>> history = new ArrayList<>();
            String convId = c.conversationId() == null ? "eval_" + UUID.randomUUID() : c.conversationId();
            String userId = c.userId() == null ? "eval_user" : c.userId();
            for (String turn : turns) {
                try {
                    var response = orchestrator.run(AgentRequest.of(turn, userId, convId, history.toString(), history));
                    QualityScores scores = judge.judge(turn, response.response(), history.toString());
                    results.add(Map.of(
                        "test_id", "dialog_" + results.size(),
                        "passed", !scores.judgeFailed() && scores.overall() >= 0.75,
                        "scores", scores.judgeFailed() ? Map.of() : Map.of(
                                "overall", round(scores.overall()),
                                "relevance", scores.relevance(),
                                "accuracy", scores.accuracy(),
                                "completeness", scores.completeness(),
                                "helpfulness", scores.helpfulness()
                        ),
                        "metadata", Map.of(
                                "question", turn,
                                "response", response.response(),
                                "agent_type", response.agentType().name().toLowerCase(Locale.ROOT),
                                "judge_failed", scores.judgeFailed(),
                                "judge_error", scores.error() == null ? "" : scores.error()
                        )
                    ));
                    history.add(Map.of("role", "user", "content", turn));
                    history.add(Map.of("role", "assistant", "content", response.response()));
                } catch (Exception ex) {
                    results.add(Map.of("test_id", "dialog_" + results.size(), "passed", false,
                            "scores", Map.of(), "metadata", Map.of("question", turn,
                                    "judge_failed", true, "execution_failed", true,
                                    "judge_error", ex.getClass().getSimpleName())));
                    history.add(Map.of("role", "user", "content", turn));
                }
            }
        }
        long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
        long judgeFallbackCount = results.stream().filter(this::judgeFallbackUsed).count();
        long dialogExecutionFailures = results.stream().filter(r -> r.get("metadata") instanceof Map<?, ?> m
                && Boolean.TRUE.equals(m.get("execution_failed"))).count();
        double intentAccuracy = intentCases.isEmpty() ? 0.0 : (double) intentCorrect / intentCases.size();
        Map<String, Object> avgScores = new HashMap<>();
        avgScores.put("intent_accuracy", IntentMetrics.round(IntentMetrics.accuracy(predictions, groundTruth)));
        avgScores.put("macro_f1", IntentMetrics.round(IntentMetrics.macroF1(predictions, groundTruth)));
        avgScores.put("dialog_overall", IntentMetrics.round(avgDialogOverall(results)));
        String datasetFingerprint = datasetFingerprint(intentCases, dialogCases);
        List<String> regressions = detectRegressions(avgScores, datasetFingerprint);
        Map<String, Object> report = new LinkedHashMap<>(Map.of(
                "pass_rate", results.isEmpty() ? 0.0 : round((double) passed / results.size()),
                "total", results.size(),
                "passed", passed,
                "avg_scores", avgScores,
                "per_class", IntentMetrics.perClass(predictions, groundTruth),
                "regressions", regressions,
                "recommendations", recommendations(intentAccuracy, regressions),
                "judge_fallback_count", judgeFallbackCount,
                "results", results
        ));
        report.put("evaluation_mode", "application_pipeline");
        report.put("dialog_scope", "AgentOrchestrator + Judge; bypasses /chat controller retrieval, Redis memory and history entity resolution");
        report.put("metrics_version", IntentMetrics.VERSION);
        report.put("dataset_fingerprint", datasetFingerprint);
        report.put("label_scope", groundTruth.stream().distinct().sorted().toList());
        report.put("intent_failure_count", intentFailureCount);
        report.put("intent_llm_failure_count", intentLlmFailureCount);
        report.put("intent_total", intentCases.size());
        long dialogTotal = results.size() - intentCases.size();
        report.put("dialog_total", dialogTotal);
        report.put("dialog_valid_count", dialogTotal - judgeFallbackCount);
        report.put("dialog_invalid_count", judgeFallbackCount);
        report.put("dialog_execution_failure_count", dialogExecutionFailures);
        report.put("judge_failure_count", judgeFallbackCount - dialogExecutionFailures);
        report.put("dialog_valid_rate", dialogTotal == 0 ? null : round((double) (dialogTotal - judgeFallbackCount) / dialogTotal));
        report.put("baseline_comparison", baselineCompatibility(datasetFingerprint));
        if (request != null && Boolean.TRUE.equals(request.saveAsBaseline())) {
            saveBaseline(report);
        }
        return report;
    }

    private List<EvalRunRequest.IntentCase> defaultIntentCases() {
        return List.of(
                new EvalRunRequest.IntentCase("我的订单什么时候到？", "logistics"),
                new EvalRunRequest.IntentCase("帮我取消订单", "request"),
                new EvalRunRequest.IntentCase("你们服务太差了！", "complaint"),
                new EvalRunRequest.IntentCase("应用一直报500错误", "technical_crash"),
                new EvalRunRequest.IntentCase("为什么扣了两次款？", "payment_issue"),
                new EvalRunRequest.IntentCase("我要投诉，转人工！", "human_handoff"),
                new EvalRunRequest.IntentCase("你好", "greeting"),
                new EvalRunRequest.IntentCase("修改我的邮箱地址", "account")
        );
    }

    @SuppressWarnings("unchecked")
    private boolean judgeFallbackUsed(Map<String, Object> result) {
        if (!String.valueOf(result.get("test_id")).startsWith("dialog_")) {
            return false;
        }
        Object metadata = result.get("metadata");
        return metadata instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("judge_failed"));
    }

    private List<EvalRunRequest.DialogCase> defaultDialogCases() {
        return List.of(
                new EvalRunRequest.DialogCase("我的订单 #12345 还没到，已经超时了", null, null, null),
                new EvalRunRequest.DialogCase("应用登录一直报错 401", null, null, null),
                new EvalRunRequest.DialogCase("为什么这个月多扣了 50 块钱？", null, null, null),
                new EvalRunRequest.DialogCase(null, List.of("你好，我想退款", "订单号是 #12345", "退款多久能到账？"), null, null)
        );
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    @SuppressWarnings("unchecked")
    private Double avgDialogOverall(List<Map<String, Object>> results) {
        var average = results.stream()
                .filter(r -> String.valueOf(r.get("test_id")).startsWith("dialog_"))
                .filter(r -> !judgeFallbackUsed(r))
                .map(r -> (Map<String, Object>) r.get("scores"))
                .mapToDouble(scores -> ((Number) scores.getOrDefault("overall", 0.0)).doubleValue())
                .average();
        return average.isPresent() ? average.getAsDouble() : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> detectRegressions(Map<String, Object> currentScores, String datasetFingerprint) {
        if (!"matched_dataset_mode_metrics".equals(baselineCompatibility(datasetFingerprint))) return List.of();
        Path path = Path.of(properties.getEval().getBaselinePath());
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            Map<String, Object> previous = objectMapper.readValue(path.toFile(), new TypeReference<>() {
            });
            Map<String, Object> prevScores = (Map<String, Object>) previous.getOrDefault("avg_scores", Map.of());
            List<String> regressions = new ArrayList<>();
            for (Map.Entry<String, Object> entry : currentScores.entrySet()) {
                Object prev = prevScores.get(entry.getKey());
                if (prev instanceof Number p && entry.getValue() instanceof Number c && p.doubleValue() > 0) {
                    double delta = (c.doubleValue() - p.doubleValue()) / p.doubleValue();
                    if (delta < -0.05) {
                        regressions.add(entry.getKey() + ": " + round(p.doubleValue()) + " -> " + round(c.doubleValue()));
                    }
                }
            }
            return regressions;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private void saveBaseline(Map<String, Object> report) {
        try {
            Path path = Path.of(properties.getEval().getBaselinePath());
            Files.createDirectories(path.toAbsolutePath().getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to save evaluation baseline", ex);
        }
    }

    private String datasetFingerprint(List<EvalRunRequest.IntentCase> intents, List<EvalRunRequest.DialogCase> dialogs) {
        try {
            Map<String, Object> cases = new LinkedHashMap<>();
            cases.put("intent_cases", intents);
            cases.put("dialog_cases", dialogs);
            return OfflineIntentBaseline.sha256(objectMapper.writeValueAsBytes(cases));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot fingerprint evaluation dataset", ex);
        }
    }

    private String baselineCompatibility(String datasetFingerprint) {
        Path path = Path.of(properties.getEval().getBaselinePath());
        if (!Files.exists(path)) return "missing";
        try {
            Map<String, Object> previous = objectMapper.readValue(path.toFile(), new TypeReference<>() { });
            if (!datasetFingerprint.equals(previous.get("dataset_fingerprint"))
                    || !IntentMetrics.VERSION.equals(previous.get("metrics_version"))
                    || !"application_pipeline".equals(previous.get("evaluation_mode"))) {
                return "incompatible_dataset_mode_or_metrics";
            }
            return "matched_dataset_mode_metrics";
        } catch (Exception ex) {
            return "unreadable";
        }
    }

    private List<String> recommendations(double intentAccuracy, List<String> regressions) {
        List<String> recs = new ArrayList<>();
        if (intentAccuracy < 0.9) {
            recs.add("分析错误类别；仅在独立开发集补充示例，不得使用候选 holdout 调参");
        }
        if (!regressions.isEmpty()) {
            recs.add("发现评测回归，请对比 baseline 中退化指标并检查最近 prompt 或检索逻辑变更");
        }
        if (recs.isEmpty()) {
            recs.add("当前未检测到所定义的回归；请同时检查样本覆盖、失败数及模型配置，不能据此宣称全部达标");
        }
        return recs;
    }
}
