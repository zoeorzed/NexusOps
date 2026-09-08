package com.echomind.monitor;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.config.EchoMindProperties;
import com.echomind.tool.KnowledgeToolManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerformanceMonitorTest {

    @Test
    void reportsNoRoutingAdjustmentWhenAllPenaltiesAreZero() {
        PerformanceMonitor monitor = monitorWithStats(Map.of(
                "billing_0", stats(1.0, 1644.5)
        ));

        monitor.collect();

        assertThat(suggestionTitles(monitor)).containsExactly("当前运行状态正常，暂无需调整路由权重");
    }

    @Test
    void reportsRoutingAdjustmentOnlyWhenPenaltyIsPositive() {
        PerformanceMonitor monitor = monitorWithStats(Map.of(
                "billing_0", stats(0.75, 4200.0)
        ));

        monitor.collect();

        assertThat(suggestionTitles(monitor)).containsExactly("路由权重已根据在线表现调整");
    }

    @Test
    void removesAlertFromActiveListAfterMetricRecovers() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        KnowledgeToolManager toolManager = mock(KnowledgeToolManager.class);
        when(orchestrator.stats())
                .thenReturn(Map.of("billing_0", stats(0.75, 4200.0)))
                .thenReturn(Map.of("billing_0", stats(1.0, 1200.0)));
        when(toolManager.stats()).thenReturn(Map.of());
        PerformanceMonitor monitor = new PerformanceMonitor(
                orchestrator, toolManager, new EchoMindProperties(), new SimpleMeterRegistry());

        monitor.collect();
        assertThat(activeAlerts(monitor)).hasSize(2);

        monitor.collect();
        assertThat(activeAlerts(monitor)).isEmpty();
    }

    private PerformanceMonitor monitorWithStats(Map<String, Object> stats) {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        KnowledgeToolManager toolManager = mock(KnowledgeToolManager.class);
        when(orchestrator.stats()).thenReturn(stats);
        when(toolManager.stats()).thenReturn(Map.of());
        return new PerformanceMonitor(
                orchestrator,
                toolManager,
                new EchoMindProperties(),
                new SimpleMeterRegistry()
        );
    }

    private Map<String, Object> stats(double successRate, double avgMs) {
        return Map.of("success_rate", successRate, "avg_ms", avgMs);
    }

    @SuppressWarnings("unchecked")
    private List<String> suggestionTitles(PerformanceMonitor monitor) {
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) monitor.summary().get("suggestions");
        return suggestions.stream().map(item -> String.valueOf(item.get("title"))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> activeAlerts(PerformanceMonitor monitor) {
        return (List<Map<String, Object>>) monitor.summary().get("active_alerts");
    }
}
