package com.echomind.evaluation;

import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class LLMJudge {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public LLMJudge(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public QualityScores judge(String question, String response, String context) {
        String prompt = """
                你是客服质量评估专家。请对以下客服响应进行评分。
                用户问题: %s
                Agent 响应: %s
                背景信息: %s

                从 relevance、accuracy、completeness、helpfulness 四个维度评分，范围 0.0-1.0。
                只返回 JSON，例如 {"relevance":0.9,"accuracy":0.8,"completeness":0.7,"helpfulness":0.85}
                """.formatted(question, response, context == null ? "" : context);
        try {
            String raw = llmGateway.chat("", prompt, 0.0, 256);
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            Map<String, Object> data = objectMapper.readValue(raw.substring(start, end + 1), new TypeReference<>() {
            });
            return new QualityScores(
                    requiredScore(data.get("relevance")),
                    requiredScore(data.get("accuracy")),
                    requiredScore(data.get("completeness")),
                    requiredScore(data.get("helpfulness")),
                    false,
                    null
            );
        } catch (Exception ex) {
            return new QualityScores(0.0, 0.0, 0.0, 0.0, true, ex.getClass().getSimpleName());
        }
    }

    private double requiredScore(Object value) {
        double score = value instanceof Number number ? number.doubleValue()
                : Double.parseDouble(String.valueOf(value));
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Judge score must be finite and within [0, 1]");
        }
        return score;
    }
}
