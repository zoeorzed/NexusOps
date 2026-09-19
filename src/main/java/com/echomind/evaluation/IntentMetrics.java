package com.echomind.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Single-label metrics; failed predictions remain in the denominator. */
final class IntentMetrics {
    static final String VERSION = "intent-v2-expected-labels-dialog-valid-only";
    static final String FAILED = "__prediction_failed__";

    private IntentMetrics() { }

    static Map<String, Map<String, Double>> perClass(List<String> predicted, List<String> expected) {
        if (predicted.size() != expected.size()) {
            throw new IllegalArgumentException("Prediction and reference counts must match");
        }
        Map<String, Map<String, Double>> metrics = new LinkedHashMap<>();
        for (String label : new TreeSet<>(expected)) {
            int tp = 0, fp = 0, fn = 0;
            for (int i = 0; i < expected.size(); i++) {
                boolean p = label.equals(predicted.get(i));
                boolean g = label.equals(expected.get(i));
                if (p && g) tp++;
                if (p && !g) fp++;
                if (!p && g) fn++;
            }
            double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
            double f1 = precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
            metrics.put(label, Map.of("precision", precision, "recall", recall, "f1", f1,
                    "support", (double) (tp + fn)));
        }
        return metrics;
    }

    static Double accuracy(List<String> predicted, List<String> expected) {
        if (predicted.size() != expected.size()) throw new IllegalArgumentException("Count mismatch");
        if (expected.isEmpty()) return null;
        long correct = 0;
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i).equals(predicted.get(i))) correct++;
        }
        return (double) correct / expected.size();
    }

    static Double macroF1(List<String> predicted, List<String> expected) {
        if (expected.isEmpty()) return null;
        return perClass(predicted, expected).values().stream()
                .mapToDouble(m -> m.get("f1")).average().orElseThrow();
    }

    static Double round(Double value) {
        return value == null ? null : Math.round(value * 10000.0) / 10000.0;
    }
}
