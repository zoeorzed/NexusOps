package com.echomind.agent;

import com.echomind.trace.ToolCallTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoadBenchmarkMetricsTest {
    @Test
    void fastRejectionsCannotBeCountedAsSuccessfulThroughputOrSuccessfulLatency() {
        List<AgentLoadBenchmark.RequestRow> rows = List.of(
                new AgentLoadBenchmark.RequestRow(0, "ok", 100, 1, "success", List.of("technical"), List.of(), List.of(), "", "OK", List.of()),
                new AgentLoadBenchmark.RequestRow(1, "rejected", 1, 1, "all_failed", List.of(), List.of("technical"), List.of("rejected"), "", "busy", List.of()));

        assertThat(AgentLoadBenchmark.throughput(rows, 2)).containsEntry("all_completed_requests", 1.0)
                .containsEntry("fully_successful_requests", 0.5);
        assertThat(AgentLoadBenchmark.counts(rows)).containsEntry("success_request_count", 1L)
                .containsEntry("rejected_request_count", 1L).containsEntry("all_failed_request_count", 1L)
                .containsEntry("request_exception_count", 0L);
        assertThat(AgentLoadBenchmark.requestRates(rows)).containsEntry("rejected_request_rate", 0.5)
                .containsEntry("success_request_rate", 0.5);
        assertThat(AgentLoadBenchmark.percentiles(rows.stream().filter(row -> row.outcome().equals("success"))
                .map(AgentLoadBenchmark.RequestRow::latencyMs).toList())).containsEntry("p50", 100.0);
    }

    @Test
    void missingSuccessSamplesStayNullAndNearestRankUsesTailForSmallSamples() {
        assertThat(AgentLoadBenchmark.percentiles(List.of())).containsEntry("count", 0)
                .containsEntry("p50", null).containsEntry("p95", null).containsEntry("p99", null);
        assertThat(AgentLoadBenchmark.percentiles(List.of(5.0, 1.0, 3.0, 2.0, 4.0)))
                .containsEntry("p50", 3.0).containsEntry("p95", 5.0).containsEntry("p99", 5.0);
    }

    @Test
    void totalFailedDomainsIncludeTimeoutAndRejectionWhileReasonRatesCanOverlap() {
        List<AgentLoadBenchmark.RequestRow> rows = List.of(
                new AgentLoadBenchmark.RequestRow(0, "mixed", 1000, 2, "all_failed", List.of(),
                        List.of("technical", "billing"), List.of("timeout", "rejected"), "", "unavailable",
                        List.of(new ToolCallTrace("agent_execution:technical", false, false, false, false, 1000, "timeout"),
                                new ToolCallTrace("agent_execution:billing", false, false, false, false, 0, "rejected"))),
                new AgentLoadBenchmark.RequestRow(1, "failed", 1, 1, "all_failed", List.of(),
                        List.of("technical"), List.of("failed"), "", "failed",
                        List.of(new ToolCallTrace("agent_execution:technical", false, false, false, false, 1, "failed"))));

        assertThat(AgentLoadBenchmark.counts(rows)).containsEntry("total_failed_domain_count", 3L)
                .containsEntry("failed_domain_count", 1L).containsEntry("timeout_domain_count", 1L)
                .containsEntry("rejected_domain_count", 1L).containsEntry("all_failed_request_count", 2L);
        assertThat(AgentLoadBenchmark.requestRates(rows)).containsEntry("all_failed_request_rate", 1.0)
                .containsEntry("timeout_request_rate", 0.5).containsEntry("rejected_request_rate", 0.5)
                .containsEntry("failed_request_rate", 0.5);
    }
}
