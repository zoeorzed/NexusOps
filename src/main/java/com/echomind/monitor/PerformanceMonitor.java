package com.echomind.monitor;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.config.EchoMindProperties;
import com.echomind.tool.KnowledgeToolManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PerformanceMonitor {

    private final AgentOrchestrator orchestrator;
    private final KnowledgeToolManager toolManager;
    private final EchoMindProperties properties;
    private final List<Map<String, Object>> alerts = new ArrayList<>();
    private final List<Map<String, Object>> suggestions = new ArrayList<>();
    private final Set<String> alertKeys = new HashSet<>();
    private final Set<String> suggestionKeys = new HashSet<>();
    private final RestClient restClient = RestClient.create();
    private final Counter monitorCollections;
    private final Timer monitorCollectionTimer;
    private volatile double agentSuccessRate = 1.0;

    public PerformanceMonitor(AgentOrchestrator orchestrator, KnowledgeToolManager toolManager, EchoMindProperties properties, MeterRegistry meterRegistry) {
        this.orchestrator = orchestrator;
        this.toolManager = toolManager;
        this.properties = properties;
        Gauge.builder("echomind_agent_success_rate", () -> agentSuccessRate).register(meterRegistry);
        this.monitorCollections = Counter.builder("echomind_monitor_collections_total").register(meterRegistry);
        this.monitorCollectionTimer = Timer.builder("echomind_monitor_collection_duration").register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        collect();
    }

    @Scheduled(fixedDelay = 10000)
    public void collect() {
        monitorCollectionTimer.record(() -> {
            monitorCollections.increment();
            Map<String, Object> agentStats = orchestrator.stats();
            Map<String, Double> penalties = new HashMap<>();
            Set<String> currentAlertKeys = new HashSet<>();
            double[] minSuccessRate = {1.0};
            agentStats.forEach((key, value) -> {
                if (value instanceof Map<?, ?> map) {
                    double sr = asDouble(map.get("success_rate"), 1.0);
                    double avg = asDouble(map.get("avg_ms"), 0.0);
                    if (sr < minSuccessRate[0]) {
                        minSuccessRate[0] = sr;
                        agentSuccessRate = sr;
                    }
                    if (sr < properties.getMonitor().getSuccessRateThreshold()) {
                        String metric = "agent_success_rate:" + key;
                        currentAlertKeys.add(metric);
                        addAlert(metric, sr, properties.getMonitor().getSuccessRateThreshold());
                    }
                    if (avg > properties.getMonitor().getLatencyMsThreshold()) {
                        String metric = "agent_avg_ms:" + key;
                        currentAlertKeys.add(metric);
                        addAlert(metric, avg, properties.getMonitor().getLatencyMsThreshold());
                    }
                    penalties.put(key, routingPenalty(sr, avg));
                }
            });
            agentSuccessRate = minSuccessRate[0];
            resolveRecoveredAlerts(currentAlertKeys);
            orchestrator.updateRoutingPenalties(penalties);
            suggestions.clear();
            suggestionKeys.clear();
            boolean routingAdjusted = penalties.values().stream().anyMatch(penalty -> penalty > 0.0);
            if (routingAdjusted) {
                addSuggestion("路由权重已根据在线表现调整", "检查 /monitor 中低成功率或高延迟 Agent，必要时优化 prompt 或增加实例。", 7);
            } else {
                addSuggestion("当前运行状态正常，暂无需调整路由权重", "继续观察 Agent 成功率和平均延迟即可。", 1);
            }
        });
    }

    public Map<String, Object> summary() {
        return Map.of(
                "agent_stats", orchestrator.stats(),
                "tool_stats", toolManager.stats(),
                "active_alerts", tail(alerts.stream()
                        .filter(alert -> !Boolean.TRUE.equals(alert.get("resolved")))
                        .toList(), 10),
                "suggestions", tail(suggestions, 5)
        );
    }

    private double routingPenalty(double successRate, double avgMs) {
        double penalty = 0.0;
        if (successRate < 0.90) {
            penalty += Math.min(0.5, (0.90 - successRate) * 2);
        }
        if (avgMs > 3000) {
            penalty += Math.min(0.4, (avgMs - 3000) / 10000);
        }
        return Math.min(0.9, penalty);
    }

    private void addAlert(String metric, double value, double threshold) {
        if (alertKeys.add(metric)) {
            Map<String, Object> alert = Map.of("metric", metric, "value", value, "threshold", threshold, "resolved", false);
            alerts.add(alert);
            sendWebhook(alert);
        }
    }

    private void resolveRecoveredAlerts(Set<String> currentAlertKeys) {
        Set<String> recovered = new HashSet<>(alertKeys);
        recovered.removeAll(currentAlertKeys);
        if (recovered.isEmpty()) {
            return;
        }
        for (int index = 0; index < alerts.size(); index++) {
            Map<String, Object> alert = alerts.get(index);
            if (recovered.contains(String.valueOf(alert.get("metric")))
                    && !Boolean.TRUE.equals(alert.get("resolved"))) {
                Map<String, Object> resolved = new HashMap<>(alert);
                resolved.put("resolved", true);
                alerts.set(index, Map.copyOf(resolved));
            }
        }
        alertKeys.removeAll(recovered);
    }

    private void addSuggestion(String title, String action, int priority) {
        if (suggestionKeys.add(title)) {
            suggestions.add(Map.of("title", title, "action", action, "priority", priority));
        }
    }

    private void sendWebhook(Map<String, Object> alert) {
        String url = properties.getMonitor().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            restClient.post().uri(url).body(alert).retrieve().toBodilessEntity();
        } catch (Exception ignored) {
        }
    }

    private double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private List<Map<String, Object>> tail(List<Map<String, Object>> values, int n) {
        return values.subList(Math.max(0, values.size() - n), values.size());
    }
}
