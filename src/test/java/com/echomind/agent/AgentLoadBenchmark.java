package com.echomind.agent;

import com.echomind.config.AgentExecutionConfig;
import com.echomind.config.AgentExecutionProperties;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;
import com.echomind.llm.LlmGateway;
import com.echomind.trace.RequestTraceStore;
import com.echomind.trace.ToolCallTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: its name deliberately does not match Surefire's default *Test patterns. */
@Timeout(60)
class AgentLoadBenchmark {
    private static final String TECHNICAL_OK = "TECHNICAL_STUB_OK";
    private static final String BILLING_OK = "BILLING_STUB_OK";

    @Test
    void measureBoundedOrchestrationWithDeterministicModelSubstitutes() throws Exception {
        Settings settings = Settings.read();
        AgentExecutionProperties properties = settings.properties();
        ThreadPoolExecutor executor = AgentExecutionConfig.newExecutor(properties);
        String executorId = "executor-" + Integer.toHexString(System.identityHashCode(executor));
        List<Map<String, Object>> stages = new ArrayList<>();
        Map<String, Boolean> checks = new LinkedHashMap<>();
        Instant startedAt = Instant.now();
        try {
            // Initialize class loading and worker threads outside all measured windows.
            AgentOrchestrator warmup = orchestrator(executor, properties, Mode.NORMAL, settings, null);
            for (int index = 0; index < 8; index++) {
                assertThat(warmup.run(request("warmup-" + index, true)).response())
                        .contains(TECHNICAL_OK, BILLING_OK);
            }
            awaitIdle(executor);

            stages.add(stage("steady_single", settings.steadyRequests, settings.threads,
                    false, Mode.NORMAL, executor, executorId, properties, settings));
            stages.add(stage("steady_multi", settings.steadyRequests, Math.max(1, settings.threads / 2),
                    true, Mode.NORMAL, executor, executorId, properties, settings));
            int burstRequests = settings.threads + settings.queueCapacity + settings.burstOverflow;
            stages.add(stage("burst_saturation", burstRequests, burstRequests,
                    false, Mode.GATED, executor, executorId, properties, settings));
            stages.add(stage("slow_timeout", settings.threads * 2, settings.threads,
                    false, Mode.SLOW, executor, executorId, properties, settings));
            stages.add(stage("partial_failure", settings.faultRequests, Math.max(1, settings.threads / 2),
                    true, Mode.TECHNICAL_FAILS, executor, executorId, properties, settings));
            stages.add(stage("all_domains_fail", settings.faultRequests, Math.max(1, settings.threads / 2),
                    true, Mode.ALL_FAIL, executor, executorId, properties, settings));
            stages.add(stage("recovery_same_executor", settings.steadyRequests, settings.threads,
                    false, Mode.NORMAL, executor, executorId, properties, settings));

            checks.put("all_requested_results_recorded", stages.stream().allMatch(stage ->
                    ((Number) stage.get("request_count")).intValue() == rows(stage).size()));
            checks.put("all_results_have_expected_domain_accounting", stages.stream().flatMap(stage -> rows(stage).stream())
                    .allMatch(row -> row.expectedDomains == row.successfulDomains.size() + row.failedDomains.size()));
            checks.put("thread_limit_respected", executor.getLargestPoolSize() <= settings.threads);
            checks.put("sampled_queue_limit_respected", stages.stream().allMatch(stage ->
                    ((Number) stage.get("peak_observed_queue_size")).intValue() <= settings.queueCapacity));
            checks.put("all_stages_use_same_executor", stages.stream().allMatch(stage -> executorId.equals(stage.get("executor_instance"))));
            checks.put("no_request_driver_exceptions", stages.stream().allMatch(stage -> count(stage, "request_exception_count") == 0));
            checks.put("steady_single_all_success", allOutcomes(stages.get(0), "success"));
            checks.put("steady_multi_all_success", allOutcomes(stages.get(1), "success"));
            checks.put("burst_rejections_observed", count(stages.get(2), "rejected_request_count") > 0);
            checks.put("burst_gate_released_after_expected_rejections", Boolean.TRUE.equals(stages.get(2).get("gate_released_after_expected_rejections")));
            checks.put("slow_calls_report_timeout", count(stages.get(3), "timeout_request_count") == settings.threads * 2);
            checks.put("partial_failure_preserves_billing_answers", rows(stages.get(4)).stream().allMatch(row ->
                    row.outcome.equals("partial_success") && row.successfulDomains.equals(List.of("billing"))
                            && row.response.contains(BILLING_OK)));
            checks.put("all_failure_is_never_success", allOutcomes(stages.get(5), "all_failed"));
            checks.put("recovery_all_success_same_pool", allOutcomes(stages.get(6), "success") && !executor.isShutdown());
            checks.put("pool_idle_after_recovery", executor.getActiveCount() == 0 && executor.getQueue().isEmpty());

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schema_version", "orchestrator-load-v1");
            report.put("started_at_utc", startedAt.toString());
            report.put("finished_at_utc", Instant.now().toString());
            report.put("scope", "In-process production AgentOrchestrator + AgentExecutionConfig + TechnicalAgent/BillingAgent + BaseAgent.handle; deterministic LlmGateway substitutes only. No HTTP, intent recognition, RAG, Redis, judge, or external model calls.");
            report.put("load_model", "Finite closed-loop client workers; synchronized first wave per stage. Burst model calls are gated until overflow requests are rejected. Warmup and inter-stage drains are outside measured windows.");
            report.put("metric_rules", metricRules());
            report.put("environment", Map.of("java_version", System.getProperty("java.version"),
                    "java_vendor", System.getProperty("java.vendor"), "os_name", System.getProperty("os.name"),
                    "os_arch", System.getProperty("os.arch"), "available_processors", Runtime.getRuntime().availableProcessors(),
                    "max_heap_bytes", Runtime.getRuntime().maxMemory()));
            report.put("configuration", settings);
            report.put("source_sha256", sourceHashes());
            report.put("warmup_requests", 8);
            report.put("checks", checks);
            report.put("all_checks_passed", checks.values().stream().allMatch(Boolean.TRUE::equals));
            report.put("stages", stages);
            Path output = Path.of(System.getProperty("load.output", "target/load-test-result.json")).toAbsolutePath();
            Files.createDirectories(output.getParent());
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("Load evidence: " + output);
            for (Map<String, Object> stage : stages) {
                System.out.println(stage.get("name") + ": " + stage.get("counts") + "; throughput=" + stage.get("throughput_per_second"));
            }
            assertThat(checks).allSatisfy((name, passed) -> assertThat(passed).as(name).isTrue());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("benchmark workers terminated").isTrue();
        }
    }

    private Map<String, Object> stage(String name, int requestCount, int concurrency, boolean multi,
                                      Mode mode, ThreadPoolExecutor executor, String executorId,
                                      AgentExecutionProperties properties, Settings settings) throws Exception {
        awaitIdle(executor);
        CountDownLatch gate = mode == Mode.GATED ? new CountDownLatch(1) : null;
        CountDownLatch rejections = new CountDownLatch(mode == Mode.GATED ? settings.burstOverflow : 0);
        AgentOrchestrator orchestrator = orchestrator(executor, properties, mode, settings, gate);
        ExecutorService clients = Executors.newFixedThreadPool(concurrency);
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger nextIndex = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();
        AtomicInteger peakQueue = new AtomicInteger();
        ConcurrentLinkedQueue<RequestRow> results = new ConcurrentLinkedQueue<>();
        Runnable sample = () -> {
            peakActive.accumulateAndGet(executor.getActiveCount(), Math::max);
            peakQueue.accumulateAndGet(executor.getQueue().size(), Math::max);
        };
        List<Future<?>> workers = new ArrayList<>();
        boolean gateReleasedAfterRejections = false;
        long elapsedNanos;
        try {
            for (int client = 0; client < concurrency; client++) {
                workers.add(clients.submit(() -> {
                    ready.countDown();
                    await(start);
                    int index;
                    while ((index = nextIndex.getAndIncrement()) < requestCount) {
                        String id = name + "-" + index;
                        sample.run();
                        long began = System.nanoTime();
                        RequestRow row;
                        try {
                            OrchestratorResult result = orchestrator.run(request(id, multi));
                            row = row(index, id, multi, began, result);
                        } catch (Exception exception) {
                            row = new RequestRow(index, id, elapsedMs(began), multi ? 2 : 1,
                                    "all_failed", List.of(), multi ? List.of("technical", "billing") : List.of("technical"),
                                    List.of("request_exception"), exception.getClass().getSimpleName(), "", List.of());
                        }
                        results.add(row);
                        if (row.failureReasons.contains("rejected")) {
                            rejections.countDown();
                        }
                        sample.run();
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("client workers ready: " + name).isTrue();
            monitor.scheduleAtFixedRate(sample, 0, 1, TimeUnit.MILLISECONDS);
            long began = System.nanoTime();
            start.countDown();
            if (gate != null) {
                // The gate makes saturation reproducible without relying on a lucky timing overlap.
                gateReleasedAfterRejections = rejections.await(Math.max(1, settings.timeoutMs / 2), TimeUnit.MILLISECONDS);
                sample.run();
                gate.countDown();
            }
            for (Future<?> worker : workers) {
                worker.get(15, TimeUnit.SECONDS);
            }
            elapsedNanos = System.nanoTime() - began;
        } finally {
            if (gate != null) {
                gate.countDown();
            }
            start.countDown();
            clients.shutdownNow();
            monitor.shutdownNow();
            clients.awaitTermination(5, TimeUnit.SECONDS);
            monitor.awaitTermination(5, TimeUnit.SECONDS);
        }
        awaitIdle(executor);
        List<RequestRow> sorted = results.stream().sorted(Comparator.comparingInt(RequestRow::index)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("executor_instance", executorId);
        result.put("request_count", requestCount);
        result.put("client_concurrency", concurrency);
        result.put("domains_per_request", multi ? 2 : 1);
        result.put("model_mode", mode.name().toLowerCase());
        result.put("duration_ms", elapsedNanos / 1_000_000.0);
        result.put("gate_released_after_expected_rejections", gate == null ? null : gateReleasedAfterRejections);
        result.put("peak_observed_active_threads", peakActive.get());
        result.put("peak_observed_queue_size", peakQueue.get());
        result.put("queue_capacity", settings.queueCapacity);
        result.put("counts", counts(sorted));
        result.put("request_rates", requestRates(sorted));
        result.put("latency_ms_all_requests", percentiles(sorted.stream().map(RequestRow::latencyMs).toList()));
        result.put("latency_ms_fully_successful_requests", percentiles(sorted.stream()
                .filter(row -> row.outcome.equals("success")).map(RequestRow::latencyMs).toList()));
        result.put("throughput_per_second", throughput(sorted, elapsedNanos / 1_000_000_000.0));
        result.put("requests", sorted);
        return result;
    }

    private AgentOrchestrator orchestrator(ThreadPoolExecutor executor, AgentExecutionProperties properties,
                                            Mode mode, Settings settings, CountDownLatch gate) {
        return new AgentOrchestrator(null, Map.of(
                AgentType.TECHNICAL, List.of(new TechnicalAgent(gateway(AgentType.TECHNICAL, mode, settings, gate), null)),
                AgentType.BILLING, List.of(new BillingAgent(gateway(AgentType.BILLING, mode, settings, gate), null))),
                new RequestTraceStore(), executor, properties);
    }

    private LlmGateway gateway(AgentType type, Mode mode, Settings settings, CountDownLatch gate) {
        return (system, prompt, temperature, maxTokens) -> {
            if (mode == Mode.ALL_FAIL || (mode == Mode.TECHNICAL_FAILS && type == AgentType.TECHNICAL)) {
                throw new IllegalStateException("controlled model exception");
            }
            try {
                if (mode == Mode.GATED) {
                    gate.await();
                }
                Thread.sleep(mode == Mode.SLOW ? settings.timeoutMs * 2 : settings.modelDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("controlled model interrupted", interrupted);
            }
            return type == AgentType.TECHNICAL ? TECHNICAL_OK : BILLING_OK;
        };
    }

    private AgentRequest request(String id, boolean multi) {
        return new AgentRequest(multi ? "登录失败提示401，而且重复扣款需要退款" : "登录失败提示401",
                "load-synthetic-user", "load-" + id, "", List.of(),
                multi ? Map.of("error_code", List.of("401"), "amount", List.of("99元")) : Map.of(),
                IntentCategory.TECHNICAL_LOGIN, "technical", UrgencyLevel.MEDIUM, 0.9, id);
    }

    private static RequestRow row(int index, String id, boolean multi, long began, OrchestratorResult result) {
        List<String> expected = multi ? List.of("technical", "billing") : List.of("technical");
        List<ToolCallTrace> failures = result.toolCalls().stream()
                .filter(call -> call.toolName().startsWith("agent_execution:") && !call.success()).toList();
        List<String> failedDomains = failures.stream().map(call -> call.toolName().substring("agent_execution:".length())).distinct().toList();
        List<String> succeeded = expected.stream().filter(domain -> !failedDomains.contains(domain))
                .filter(domain -> result.response().contains(domain.equals("technical") ? TECHNICAL_OK : BILLING_OK)).toList();
        String outcome = succeeded.size() == expected.size() ? "success" : succeeded.isEmpty() ? "all_failed" : "partial_success";
        return new RequestRow(index, id, elapsedMs(began), expected.size(), outcome, succeeded, failedDomains,
                failures.stream().map(ToolCallTrace::error).distinct().toList(), "", result.response(), result.toolCalls());
    }

    static Map<String, Long> counts(List<RequestRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("completed_request_count", (long) rows.size());
        counts.put("success_request_count", rows.stream().filter(row -> row.outcome.equals("success")).count());
        counts.put("partial_success_request_count", rows.stream().filter(row -> row.outcome.equals("partial_success")).count());
        counts.put("all_failed_request_count", rows.stream().filter(row -> row.outcome.equals("all_failed")).count());
        counts.put("successful_domain_count", rows.stream().mapToLong(row -> row.successfulDomains.size()).sum());
        counts.put("total_failed_domain_count", rows.stream().mapToLong(row -> row.failedDomains.size()).sum());
        for (String reason : List.of("rejected", "timeout", "failed", "interrupted")) {
            counts.put(reason + "_request_count", rows.stream().filter(row -> row.failureReasons.contains(reason)).count());
            counts.put(reason + "_domain_count", rows.stream().flatMap(row -> row.toolCalls.stream())
                    .filter(call -> !call.success() && reason.equals(call.error())).count());
        }
        counts.put("request_exception_count", rows.stream().filter(row -> row.failureReasons.contains("request_exception")).count());
        return counts;
    }

    static Map<String, Double> requestRates(List<RequestRow> rows) {
        Map<String, Long> counts = counts(rows);
        Map<String, Double> rates = new LinkedHashMap<>();
        for (String kind : List.of("success", "partial_success", "all_failed", "rejected", "timeout", "failed", "interrupted")) {
            rates.put(kind + "_request_rate", rows.isEmpty() ? null : counts.get(kind + "_request_count") / (double) rows.size());
        }
        rates.put("request_exception_rate", rows.isEmpty() ? null : counts.get("request_exception_count") / (double) rows.size());
        return rates;
    }

    static Map<String, Double> throughput(List<RequestRow> rows, double seconds) {
        long fullySuccessful = rows.stream().filter(row -> row.outcome.equals("success")).count();
        return Map.of("all_completed_requests", rows.size() / seconds, "fully_successful_requests", fullySuccessful / seconds);
    }

    static Map<String, Object> percentiles(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", sorted.size());
        result.put("p50", percentile(sorted, 0.50));
        result.put("p95", percentile(sorted, 0.95));
        result.put("p99", percentile(sorted, 0.99));
        return result;
    }

    private static Double percentile(List<Double> sorted, double quantile) {
        return sorted.isEmpty() ? null : sorted.get(Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1));
    }

    private Map<String, String> metricRules() {
        return Map.of("latency", "Monotonic time around orchestrator.run, includes Agent queue wait and BaseAgent prompt handling; excludes client executor scheduling before invocation.",
                "percentile", "Nearest rank: sorted[ceil(p*n)-1]; null when no samples, never zero for missing successful samples.",
                "outcome", "Mutually exclusive success / partial_success / all_failed from expected domains, synthetic response markers and agent_execution failure traces.",
                "failure_reason", "Rejected/timeout/failed/interrupted/request_exception counts are independent request-level presence counts and can overlap. Rates divide by all measured requests. Domain counts use failure traces. Controlled model exceptions map to failed; request_exception means an exception escaped orchestrator.run, which returns no trace.",
                "throughput", "Fully successful requests / measured stage seconds; all completed requests / same seconds is separate and includes fast rejection. Neither is real-model QPS or HTTP capacity.",
                "peaks", "Active-thread and queue-size observations sampled every 1ms and at request boundaries. Observed maxima are lower bounds on instantaneous peaks; queue capacity is structurally bounded.",
                "drain", "Every stage starts/ends with no active Agent tasks and an empty queue; all stages reuse one real executor. Timed-out stub calls cooperate with interruption; real HTTP cancellation is not established.");
    }

    private static Map<String, String> sourceHashes() throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String file : List.of("src/main/java/com/echomind/agent/AgentOrchestrator.java",
                "src/main/java/com/echomind/agent/BaseAgent.java", "src/main/java/com/echomind/agent/TechnicalAgent.java",
                "src/main/java/com/echomind/agent/BillingAgent.java", "src/main/java/com/echomind/agent/AgentRequest.java",
                "src/main/java/com/echomind/config/AgentExecutionConfig.java",
                "src/main/java/com/echomind/config/AgentExecutionProperties.java", "src/test/java/com/echomind/agent/AgentLoadBenchmark.java")) {
            hashes.put(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(file)))));
        }
        return hashes;
    }

    private static void awaitIdle(ThreadPoolExecutor executor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((executor.getActiveCount() != 0 || !executor.getQueue().isEmpty()) && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertThat(executor.getActiveCount()).as("no Agent task survives stage drain").isZero();
        assertThat(executor.getQueue()).as("Agent queue drained").isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("load driver interrupted", interrupted);
        }
    }

    private static double elapsedMs(long began) {
        return (System.nanoTime() - began) / 1_000_000.0;
    }

    @SuppressWarnings("unchecked")
    private static List<RequestRow> rows(Map<String, Object> stage) {
        return (List<RequestRow>) stage.get("requests");
    }

    @SuppressWarnings("unchecked")
    private static long count(Map<String, Object> stage, String field) {
        return ((Map<String, Long>) stage.get("counts")).get(field);
    }

    private static boolean allOutcomes(Map<String, Object> stage, String outcome) {
        return rows(stage).stream().allMatch(row -> row.outcome.equals(outcome));
    }

    enum Mode { NORMAL, GATED, SLOW, TECHNICAL_FAILS, ALL_FAIL }

    record RequestRow(int index, String requestId, double latencyMs, int expectedDomains, String outcome,
                      List<String> successfulDomains, List<String> failedDomains, List<String> failureReasons,
                      String requestException, String response, List<ToolCallTrace> toolCalls) { }

    record Settings(int threads, int queueCapacity, long timeoutMs, long modelDelayMs,
                    int steadyRequests, int faultRequests, int burstOverflow) {
        static Settings read() {
            Settings result = new Settings(integer("threads", 4), integer("queueCapacity", 8),
                    integer("timeoutMs", 1000), integer("modelDelayMs", 30), integer("steadyRequests", 40),
                    integer("faultRequests", 12), integer("burstOverflow", 8));
            if (result.threads < 2 || result.threads > 16 || result.queueCapacity < 1 || result.queueCapacity > 64
                    || result.timeoutMs < 200 || result.timeoutMs > 3000 || result.modelDelayMs < 1 || result.modelDelayMs > 100
                    || result.steadyRequests < result.threads || result.steadyRequests > 100
                    || result.faultRequests < 1 || result.faultRequests > 50 || result.burstOverflow < 1 || result.burstOverflow > 32) {
                throw new IllegalArgumentException("Unsupported benchmark parameters; see docs/load-testing.md");
            }
            return result;
        }

        AgentExecutionProperties properties() {
            AgentExecutionProperties result = new AgentExecutionProperties();
            result.setThreads(threads);
            result.setQueueCapacity(queueCapacity);
            result.setTimeoutMs(timeoutMs);
            return result;
        }

        private static int integer(String key, int fallback) {
            return Integer.parseInt(System.getProperty("load." + key, Integer.toString(fallback)));
        }
    }
}
