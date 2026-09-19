package com.echomind.evaluation;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.intent.IntentRecognizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Production fallback baseline; the injected gateway always fails and cannot make network calls. */
public final class OfflineIntentBaseline {
    private OfflineIntentBaseline() { }

    public static void main(String[] args) throws Exception {
        Path dataset = Path.of(args.length > 0 ? args[0] : "evaluation/intent-holdout-candidate-v1.json");
        Path output = Path.of(args.length > 1 ? args[1] : "target/offline-intent-baseline.json");
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        EvalRunRequest candidates = mapper.readValue(Files.readString(dataset), EvalRunRequest.class);
        Map<String, Object> report = evaluate(candidates.intentCases());
        report.put("dataset", dataset.toString());
        report.put("dataset_sha256", sha256(Files.readAllBytes(dataset)));
        if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("OFFLINE production IntentRecognizer fallback; no model, agent, RAG or judge was run.");
        System.out.println("total=" + report.get("total") + ", scores=" + report.get("avg_scores"));
        System.out.println("Report: " + output.toAbsolutePath());
    }

    static Map<String, Object> evaluate(List<EvalRunRequest.IntentCase> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Nonempty candidate cases are required");
        }
        IntentRecognizer recognizer = new IntentRecognizer((system, user, temperature, tokens) -> {
            throw new IllegalStateException("Offline baseline: model gateway deliberately disabled");
        }, new ObjectMapper());
        List<String> predicted = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (var item : cases) {
            String prediction = recognizer.recognize(item.message(), null).intent().name().toLowerCase(Locale.ROOT);
            predicted.add(prediction);
            expected.add(item.expectedIntent());
            results.add(Map.of("message", item.message(), "expected", item.expectedIntent(),
                    "predicted", prediction, "passed", prediction.equals(item.expectedIntent())));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluation_mode", "offline_production_intent_fallback");
        report.put("metrics_version", IntentMetrics.VERSION);
        report.put("scope", "19-class synthetic candidate set; not an LLM, RAG, agent or independent human evaluation");
        report.put("algorithm", "Unmodified IntentRecognizer with failing LlmGateway: specific keyword rules, then template character n-gram similarity, then generic rules");
        report.put("reproducibility_note", "Equal-score Map iteration ties in production can vary between JVM runs; keep per-case predictions and do not claim bitwise repeatability.");
        report.put("label_scope", expected.stream().distinct().sorted().toList());
        report.put("total", cases.size());
        report.put("passed", results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count());
        report.put("avg_scores", Map.of("intent_accuracy", IntentMetrics.round(IntentMetrics.accuracy(predicted, expected)),
                "macro_f1", IntentMetrics.round(IntentMetrics.macroF1(predicted, expected))));
        report.put("per_class", IntentMetrics.perClass(predicted, expected));
        report.put("results", results);
        return report;
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
