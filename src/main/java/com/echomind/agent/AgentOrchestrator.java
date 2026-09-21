package com.echomind.agent;

import com.echomind.config.AgentExecutionConfig;
import com.echomind.config.AgentExecutionProperties;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import com.echomind.trace.RequestTraceStore;
import com.echomind.trace.RequestToolTrace;
import com.echomind.trace.ToolCallTrace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AgentOrchestrator {

    private static final double SUPPORTING_AGENT_MIN_SCORE = 0.30;
    private static final double SUPPORTING_AGENT_PRIMARY_RATIO = 0.30;
    private static final String[] TECHNICAL_SIGNALS = {
            "闪退", "崩溃", "无法登录", "不能登录", "登录失败", "登录不进去", "登录不上",
            "账号被盗", "账户被盗", "异地登录", "密码被改"
    };
    private static final String[] BILLING_SIGNALS = {
            "重复扣款", "多扣", "扣了两次", "扣款两次", "异常扣款", "重复收费"
    };

    private final IntentRecognizer intentRecognizer;
    private final Map<AgentType, List<BaseAgent>> pool;
    private final RequestTraceStore traceStore;
    private final ThreadPoolExecutor executor;
    private final long executionTimeoutNanos;
    private final Map<IntentCategory, AgentType> routing = new EnumMap<>(IntentCategory.class);

    public AgentOrchestrator(IntentRecognizer intentRecognizer, Map<AgentType, List<BaseAgent>> pool, RequestTraceStore traceStore) {
        this(intentRecognizer, pool, traceStore, DefaultExecutor.INSTANCE, new AgentExecutionProperties());
    }

    @Autowired
    public AgentOrchestrator(
            IntentRecognizer intentRecognizer,
            Map<AgentType, List<BaseAgent>> pool,
            RequestTraceStore traceStore,
            @Qualifier("agentExecutor") ThreadPoolExecutor executor,
            AgentExecutionProperties properties
    ) {
        this.intentRecognizer = intentRecognizer;
        this.pool = pool;
        this.traceStore = traceStore;
        this.executor = executor;
        this.executionTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(properties.getTimeoutMs());
        routing.put(IntentCategory.TECHNICAL, AgentType.TECHNICAL);
        routing.put(IntentCategory.TECHNICAL_LOGIN, AgentType.TECHNICAL);
        routing.put(IntentCategory.TECHNICAL_CRASH, AgentType.TECHNICAL);
        routing.put(IntentCategory.BILLING, AgentType.BILLING);
        routing.put(IntentCategory.REFUND, AgentType.BILLING);
        routing.put(IntentCategory.INVOICE, AgentType.BILLING);
        routing.put(IntentCategory.PAYMENT_ISSUE, AgentType.BILLING);
        routing.put(IntentCategory.ACCOUNT, AgentType.BILLING);
        routing.put(IntentCategory.ACCOUNT_SECURITY, AgentType.TECHNICAL);
        routing.put(IntentCategory.ESCALATION, AgentType.ESCALATION);
        routing.put(IntentCategory.HUMAN_HANDOFF, AgentType.ESCALATION);
    }

    public OrchestratorResult run(AgentRequest request) {
        return run(request, List.of());
    }

    public OrchestratorResult run(AgentRequest request, List<ToolCallTrace> externalToolCalls) {
        Instant start = Instant.now();
        AgentRequest req = request;
        if (req.intent() == null) {
            IntentResult intentResult = intentRecognizer.recognize(req.message(), req.history());
            req = req.withIntent(intentResult);
        }

        if (needsClarification(req)) {
            OrchestratorResult result = new OrchestratorResult(
                    req.requestId(),
                    "我还不能确定您要处理的是哪类问题。请补充一下是订单物流、退款账单、账户资料，还是技术故障？",
                    AgentType.GENERAL,
                    req.intent(),
                    false,
                    Duration.between(start, Instant.now()).toMillis(),
                    List.of(AgentType.GENERAL),
                    AgentType.GENERAL,
                    List.of(),
                    collectToolNames(externalToolCalls),
                    collectToolCalls(externalToolCalls),
                    "低置信度 OTHER 意图，先澄清用户需求",
                    req.intentConfidence()
            );
            recordTrace(req, result);
            return result;
        }

        RoutingDecision decision = routeDecision(req);
        if (decision.multiAgent()) {
            return runParallel(req, decision, externalToolCalls, start);
        }

        AgentResponse response = executeWithinDeadline(req, decision).getFirst();
        boolean escalated = response.escalate()
                || req.urgency() == UrgencyLevel.CRITICAL
                || req.intent() == IntentCategory.ESCALATION
                || req.intent() == IntentCategory.HUMAN_HANDOFF;
        OrchestratorResult result = new OrchestratorResult(
                req.requestId(),
                response.content(),
                response.agentType(),
                req.intent(),
                escalated,
                Duration.between(start, Instant.now()).toMillis(),
                List.of(response.agentType()),
                decision.primaryAgent(),
                List.of(),
                collectToolNames(externalToolCalls, response),
                collectToolCalls(externalToolCalls, response),
                decision.reason(),
                decision.confidence()
        );
        recordTrace(req, result);
        return result;
    }

    public Optional<RequestToolTrace> getToolTrace(String requestId) {
        return traceStore.find(requestId);
    }

    public List<RequestToolTrace> getRecentToolTraces(int limit) {
        return traceStore.recent(limit);
    }

    public void updateTraceEscalated(String requestId, boolean escalated) {
        traceStore.updateEscalated(requestId, escalated);
    }

    private OrchestratorResult runParallel(AgentRequest req, RoutingDecision decision, List<ToolCallTrace> externalToolCalls, Instant start) {
        List<AgentType> targets = decision.agentTypes();
        List<AgentResponse> responses = executeWithinDeadline(req, decision);
        List<String> parts = new ArrayList<>();
        for (AgentResponse response : responses) {
            if (response.success()) {
                parts.add("## " + domainTitle(response.agentType()) + "\n\n" + response.content());
            }
        }
        String content = parts.isEmpty() ? "抱歉，暂时无法完成这些问题的处理。" : String.join("\n\n", parts);
        List<String> failures = responses.stream()
                .filter(response -> !response.success())
                .map(AgentResponse::content)
                .toList();
        if (!failures.isEmpty()) {
            content += "\n\n" + String.join("\n", failures);
        }
        boolean escalate = responses.stream().anyMatch(AgentResponse::escalate)
                || req.urgency() == UrgencyLevel.CRITICAL
                || req.intent() == IntentCategory.ESCALATION
                || req.intent() == IntentCategory.HUMAN_HANDOFF;
        List<AgentType> agentTypes = responses.stream()
                .filter(AgentResponse::success)
                .map(AgentResponse::agentType)
                .toList();
        OrchestratorResult result = new OrchestratorResult(
                req.requestId(),
                content,
                decision.primaryAgent(),
                req.intent(),
                escalate,
                Duration.between(start, Instant.now()).toMillis(),
                agentTypes.isEmpty() ? targets : agentTypes,
                decision.primaryAgent(),
                decision.supportingAgents(),
                collectToolNames(externalToolCalls, responses),
                collectToolCalls(externalToolCalls, responses),
                decision.reason(),
                decision.confidence()
        );
        recordTrace(req, result);
        return result;
    }

    private List<AgentResponse> executeWithinDeadline(AgentRequest request, RoutingDecision decision) {
        // One monotonic deadline for the whole fan-out; queue waiting consumes the same budget.
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + executionTimeoutNanos;
        List<AgentType> targets = decision.agentTypes();
        List<PendingAgent> pending = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            AgentType type = targets.get(index);
            AgentRequest scoped = decision.multiAgent()
                    ? scopedRequest(request, type)
                    : request;
            if (deadlineNanos - System.nanoTime() <= 0) {
                pending.add(new PendingAgent(type, null, "timeout"));
                continue;
            }
            try {
                Future<CompletedAgent> future = executor.submit(() -> {
                    AgentResponse response = execute(scoped, type, deadlineNanos);
                    return new CompletedAgent(response, System.nanoTime());
                });
                pending.add(new PendingAgent(type, future, null));
            } catch (RejectedExecutionException rejected) {
                pending.add(new PendingAgent(type, null, "rejected"));
            }
        }

        List<AgentResponse> responses = new ArrayList<>();
        for (PendingAgent task : pending) {
            if (task.failure() != null) {
                responses.add(executionFailure(task.type(), task.failure(), startedNanos));
                continue;
            }
            try {
                // get(0) still collects a completed sibling after another Agent used the budget.
                CompletedAgent completed = task.future().get(
                        Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
                responses.add(completed.finishedNanos() - deadlineNanos <= 0
                        ? completed.response()
                        : executionFailure(task.type(), "timeout", startedNanos));
            } catch (TimeoutException timedOut) {
                cancel(task.future());
                responses.add(executionFailure(task.type(), "timeout", startedNanos));
            } catch (InterruptedException interrupted) {
                cancel(task.future());
                Thread.currentThread().interrupt();
                responses.add(executionFailure(task.type(), "interrupted", startedNanos));
            } catch (ExecutionException | CancellationException failed) {
                responses.add(executionFailure(task.type(), "failed", startedNanos));
            }
        }
        return responses;
    }

    private void cancel(Future<?> future) {
        // This requests interruption only. An underlying HTTP client may continue until its own timeout.
        future.cancel(true);
        if (future instanceof Runnable queuedTask) {
            executor.remove(queuedTask);
        }
    }

    private AgentResponse executionFailure(AgentType type, String reason, long startedNanos) {
        String domain = domainTitle(type);
        String message = switch (reason) {
            case "timeout" -> domain + "处理超时，请稍后重试。";
            case "rejected" -> domain + "处理服务繁忙，请稍后重试。";
            case "interrupted" -> domain + "处理已中断，请稍后重试。";
            default -> domain + "暂时处理失败，请稍后重试。";
        };
        return new AgentResponse(type, message, false, 0.0,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos), false,
                "agent_execution:" + type.name().toLowerCase(Locale.ROOT), false, false, false, reason);
    }

    private record PendingAgent(AgentType type, Future<CompletedAgent> future, String failure) {
    }

    private record CompletedAgent(AgentResponse response, long finishedNanos) {
    }

    private static class DefaultExecutor {
        private static final ThreadPoolExecutor INSTANCE = AgentExecutionConfig.newExecutor(new AgentExecutionProperties());
    }

    private String domainTitle(AgentType type) {
        return switch (type) {
            case TECHNICAL -> "技术问题";
            case BILLING -> "账务问题";
            case ESCALATION -> "人工支持请求";
            case GENERAL -> "订单与服务";
        };
    }

    private AgentRequest scopedRequest(AgentRequest request, AgentType agentType) {
        String original = request.message() == null ? "" : request.message();
        String scopedMessage;
        Map<String, List<String>> scopedEntities = new LinkedHashMap<>();
        Map<String, List<String>> entities = request.entities() == null ? Map.of() : request.entities();
        copyEntity(entities, scopedEntities, "order_id");

        if (agentType == AgentType.TECHNICAL) {
            copyScopedDates(entities, scopedEntities, original, agentType);
            copyEntity(entities, scopedEntities, "error_code");
            scopedMessage = """
                    [用户请求中与技术相关的内容]
                    %s

                    [技术子任务]
                    你当前只负责技术问题。只处理账户保护、登录恢复、认证、系统故障和订单不可见等技术问题；不要回答扣款、退款、到账或财务审核。
                    回复中不要复述、解释或提示账务问题，也不要介绍内部角色、协同流程或结果展示顺序。
                    回答完本领域问题后直接结束，不要添加处理范围说明或跨领域转交提示。
                    如确需人工后台排查，只说明需要人工后台排查；不得声称已记录、将记录、已提交、将提交或会继续跟进。
                    """.formatted(domainMessage(original, AgentType.TECHNICAL));
        } else if (agentType == AgentType.BILLING) {
            copyScopedDates(entities, scopedEntities, original, agentType);
            copyEntity(entities, scopedEntities, "amount");
            scopedMessage = """
                    [用户请求中与账务相关的内容]
                    %s

                    [账务子任务]
                    你当前只负责账务问题。只处理扣款、支付、退款、账单和流水核验；不要回答登录、401、缓存或技术排障。
                    回复中不要复述、解释或提示技术问题，也不要介绍内部角色、协同流程或结果展示顺序。
                    回答完本领域问题后直接结束，不要添加处理范围说明或跨领域转交提示。
                    区分扣款核验、退款申请审核和审核通过后到账三个阶段；只引用知识库中对应阶段的规则与起算点，没有该阶段时限时明确未知，不得将审核时限当作到账承诺。
                    多扣金额的争议不等于普通商品无理由退货退款；知识未明确说明适用时，不能套用七天申请期限、先退货条件或无理由退款时限，先说明核验争议的下一步即可。
                    退款的审核主体、适用条件只按知识库说明；当前对话无法执行退款不代表平台必须人工或财务审核。不得声称当前对话已经转人工或已经完成退款。
                    """.formatted(domainMessage(original, AgentType.BILLING));
        } else {
            scopedEntities.putAll(entities);
            scopedMessage = original;
        }

        return new AgentRequest(
                scopedMessage,
                request.userId(),
                request.conversationId(),
                request.context(),
                request.history(),
                scopedEntities,
                request.intent(),
                request.intentGroup(),
                request.urgency(),
                request.intentConfidence(),
                request.requestId()
        );
    }

    private String domainMessage(String original, AgentType agentType) {
        List<String> relevant = domainClauses(original, agentType);
        return relevant.isEmpty() ? "本轮没有明确的本领域问题，请勿推断或展开其他领域内容。" : String.join("；", relevant);
    }

    private List<String> domainClauses(String original, AgentType agentType) {
        String[] keywords = agentType == AgentType.TECHNICAL
                ? new String[]{"登录", "401", "认证", "凭证", "报错", "错误", "故障", "闪退", "崩溃", "查不到", "不可见", "系统",
                        "账号被盗", "账户被盗", "账户安全", "账号安全", "密码被改", "账户保护", "账号保护"}
                : new String[]{"扣款", "支付", "退款", "账单", "发票", "流水", "金额", "多扣", "扣了两次", "重复收费"};
        return Arrays.stream(clauses(original))
                .map(String::trim)
                .filter(part -> countActiveHits(part.toLowerCase(Locale.ROOT), keywords) > 0)
                .toList();
    }

    private void copyScopedDates(Map<String, List<String>> source, Map<String, List<String>> target,
                                 String original, AgentType agentType) {
        AgentType otherDomain = agentType == AgentType.TECHNICAL ? AgentType.BILLING : AgentType.TECHNICAL;
        List<String> otherClauses = domainClauses(original, otherDomain);
        List<String> ownClauses = domainClauses(original, agentType).stream()
                .filter(clause -> !otherClauses.contains(clause))
                .toList();
        // Copy only verbatim dates in an unambiguous domain clause. Do not infer attribution
        // from global entities or convert dates; the scoped original wording remains available.
        List<String> dates = source.getOrDefault("date", List.of()).stream()
                .filter(date -> date != null && !date.isBlank())
                .filter(date -> ownClauses.stream().anyMatch(clause -> clause.contains(date)))
                .toList();
        if (!dates.isEmpty()) {
            target.put("date", dates);
        }
    }

    private void copyEntity(
            Map<String, List<String>> source,
            Map<String, List<String>> target,
            String name
    ) {
        List<String> values = source.getOrDefault(name, List.of());
        if (!values.isEmpty()) {
            target.put(name, values);
        }
    }

    private AgentType route(IntentCategory intent, UrgencyLevel urgency) {
        if (urgency == UrgencyLevel.CRITICAL) {
            return AgentType.ESCALATION;
        }
        AgentType target = routing.get(intent);
        if (target != null && pool.containsKey(target)) {
            return target;
        }
        return AgentType.GENERAL;
    }

    private RoutingDecision routeDecision(AgentRequest req) {
        if (req.urgency() == UrgencyLevel.CRITICAL) {
            return new RoutingDecision(AgentType.ESCALATION, List.of(), "紧急度为 CRITICAL，触发升级路由", 1.0);
        }
        if (req.intent() == IntentCategory.ESCALATION || req.intent() == IntentCategory.HUMAN_HANDOFF) {
            String intent = req.intent() == null ? "unknown" : req.intent().name().toLowerCase(Locale.ROOT);
            return new RoutingDecision(
                    AgentType.ESCALATION,
                    List.of(),
                    "意图为 " + intent + "，触发升级路由",
                    Math.max(req.intentConfidence(), 0.8)
            );
        }

        Map<AgentType, Double> scores = domainScores(req);
        Map<AgentType, Double> availableScores = new EnumMap<>(AgentType.class);
        scores.forEach((agentType, score) -> {
            if (agentType == AgentType.GENERAL || pool.containsKey(agentType)) {
                availableScores.put(agentType, score);
            }
        });
        if (availableScores.isEmpty()) {
            return new RoutingDecision(AgentType.GENERAL, List.of(), "无可用专属 Agent，降级到 GeneralAgent", 0.1);
        }

        List<Map.Entry<AgentType, Double>> specificCandidates = availableScores.entrySet().stream()
                .filter(entry -> entry.getKey() != AgentType.GENERAL)
                .filter(entry -> entry.getValue() >= SUPPORTING_AGENT_MIN_SCORE)
                .toList();
        List<Map.Entry<AgentType, Double>> candidates = specificCandidates.isEmpty()
                ? new ArrayList<>(availableScores.entrySet())
                : specificCandidates;
        List<Map.Entry<AgentType, Double>> ordered = candidates.stream()
                .sorted(Map.Entry.<AgentType, Double>comparingByValue().reversed())
                .toList();
        AgentType primary = ordered.getFirst().getKey();
        double primaryScore = ordered.getFirst().getValue();
        double normalizedPrimaryScore = Math.min(primaryScore, 1.0);
        List<AgentType> supportingAgents = ordered.stream()
                .skip(1)
                .filter(entry -> entry.getKey() != AgentType.GENERAL)
                .filter(entry -> entry.getValue() >= SUPPORTING_AGENT_MIN_SCORE
                        && entry.getValue() >= normalizedPrimaryScore * SUPPORTING_AGENT_PRIMARY_RATIO)
                .map(Map.Entry::getKey)
                .toList();
        return new RoutingDecision(
                primary,
                supportingAgents,
                routingReason(req, availableScores, primary, supportingAgents),
                round(Math.min(primaryScore, 1.0))
        );
    }

    private Map<AgentType, Double> domainScores(AgentRequest req) {
        String msg = req.message() == null ? "" : req.message().toLowerCase(Locale.ROOT);
        Map<AgentType, Double> scores = new EnumMap<>(AgentType.class);
        scores.put(AgentType.GENERAL, 0.1);
        scores.put(AgentType.TECHNICAL, 0.0);
        scores.put(AgentType.BILLING, 0.0);

        if (List.of(
                IntentCategory.QUERY,
                IntentCategory.ORDER_STATUS,
                IntentCategory.LOGISTICS,
                IntentCategory.REQUEST,
                IntentCategory.COMPLAINT,
                IntentCategory.GREETING,
                IntentCategory.FEEDBACK,
                IntentCategory.OTHER
        ).contains(req.intent())) {
            scores.merge(AgentType.GENERAL, 0.55, Double::sum);
        }

        if (List.of(
                IntentCategory.TECHNICAL,
                IntentCategory.TECHNICAL_LOGIN,
                IntentCategory.TECHNICAL_CRASH,
                IntentCategory.ACCOUNT_SECURITY
        ).contains(req.intent())) {
            scores.merge(AgentType.TECHNICAL, 0.75, Double::sum);
        }

        if (List.of(
                IntentCategory.BILLING,
                IntentCategory.ACCOUNT,
                IntentCategory.REFUND,
                IntentCategory.INVOICE,
                IntentCategory.PAYMENT_ISSUE
        ).contains(req.intent())) {
            scores.merge(AgentType.BILLING, 0.75, Double::sum);
        }

        long technicalHits = countActiveHits(msg, "崩溃", "报错", "error", "crash", "无法登录", "登录失败", "500", "401", "验证码");
        long billingHits = countActiveHits(msg, "退款", "退货", "扣款", "发票", "账单", "支付", "订阅", "refund", "invoice", "多扣");
        long generalHits = countHits(msg, "订单", "物流", "快递", "配送", "会员", "积分", "咨询", "帮助");

        scores.merge(AgentType.TECHNICAL, Math.min(0.45, technicalHits * 0.18), Double::sum);
        scores.merge(AgentType.BILLING, Math.min(0.45, billingHits * 0.18), Double::sum);
        scores.merge(AgentType.GENERAL, Math.min(0.35, generalHits * 0.12), Double::sum);

        // One explicit, current problem is sufficient evidence for a supporting domain.
        // This does not lower the threshold for ambiguous words such as "支付" or "系统".
        if (countActiveHits(msg, TECHNICAL_SIGNALS) > 0) {
            scores.merge(AgentType.TECHNICAL, 0.35, Math::max);
        }
        if (countActiveHits(msg, BILLING_SIGNALS) > 0) {
            scores.merge(AgentType.BILLING, 0.35, Math::max);
        }

        Map<String, List<String>> entities = req.entities() == null ? Map.of() : req.entities();
        if (!entities.getOrDefault("error_code", List.of()).isEmpty()) {
            scores.merge(AgentType.TECHNICAL, 0.2, Double::sum);
        }
        if (!entities.getOrDefault("amount", List.of()).isEmpty()) {
            scores.merge(AgentType.BILLING, 0.15, Double::sum);
        }
        if (!entities.getOrDefault("order_id", List.of()).isEmpty()) {
            scores.merge(AgentType.GENERAL, 0.1, Double::sum);
        }

        scores.replaceAll((agentType, score) -> round(score));
        return scores;
    }

    private String routingReason(
            AgentRequest req,
            Map<AgentType, Double> scores,
            AgentType primaryAgent,
            List<AgentType> supportingAgents
    ) {
        String scoreText = scores.entrySet().stream()
                .sorted(Map.Entry.<AgentType, Double>comparingByValue().reversed())
                .map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT) + "=" + String.format(Locale.ROOT, "%.2f", entry.getValue()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String supportText = supportingAgents.stream()
                .map(agent -> agent.name().toLowerCase(Locale.ROOT))
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        String intent = req.intent() == null ? "unknown" : req.intent().name().toLowerCase(Locale.ROOT);
        return "intent=" + intent
                + ", group=" + (req.intentGroup() == null ? "unknown" : req.intentGroup())
                + ", primary=" + primaryAgent.name().toLowerCase(Locale.ROOT)
                + ", supporting=" + supportText
                + ", scores=[" + scoreText + "]";
    }

    private List<AgentType> collaborationTargets(AgentRequest request) {
        String msg = request.message() == null ? "" : request.message().toLowerCase(Locale.ROOT);
        LinkedHashSet<AgentType> targets = new LinkedHashSet<>();
        if (request.intent() == IntentCategory.TECHNICAL || containsAny(msg, "崩溃", "报错", "error", "crash", "无法登录", "500", "401")) {
            targets.add(AgentType.TECHNICAL);
        }
        if (request.intent() == IntentCategory.BILLING || request.intent() == IntentCategory.ACCOUNT
                || containsAny(msg, "退款", "扣款", "发票", "账单", "支付", "订阅", "refund", "invoice")) {
            targets.add(AgentType.BILLING);
        }
        return new ArrayList<>(targets.stream().filter(pool::containsKey).toList());
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private long countHits(String text, String... keywords) {
        long hits = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                hits++;
            }
        }
        return hits;
    }

    private String[] clauses(String text) {
        return text.split("(?<=[，,。；;！？!?\\n])|(?:而且|并且|同时|另外|但是|不过|但)");
    }

    private long countActiveHits(String text, String... keywords) {
        return Arrays.stream(keywords)
                .filter(keyword -> Arrays.stream(clauses(text)).anyMatch(clause -> hasActiveMention(clause, keyword)))
                .count();
    }

    private boolean hasActiveMention(String clause, String keyword) {
        for (int index = clause.indexOf(keyword); index >= 0; index = clause.indexOf(keyword, index + keyword.length())) {
            String before = clause.substring(0, index);
            String after = clause.substring(index + keyword.length());
            // Deliberately limited to obvious local denial/resolution, not a full language parser.
            boolean denied = before.matches("(?s).*(?:没有|并未|并无|未出现|不存在|没出现|不是|不再|已经不|现在不).{0,6}")
                    || before.matches("(?s).*(?:已解决|已修复|已恢复)的?");
            boolean resolved = after.matches("(?s)^(?:款|费)?(?:的问题|问题|故障|现象)?(?:已经|已|现在|目前)?(?:解决|修复|恢复|消失|正常).*");
            if (!denied && !resolved) {
                return true;
            }
        }
        return false;
    }

    private boolean needsClarification(AgentRequest req) {
        if (req.intent() != IntentCategory.OTHER) {
            return false;
        }
        String text = req.message() == null ? "" : req.message().trim();
        if (text.length() <= 2) {
            return false;
        }
        return req.intentConfidence() < 0.5;
    }

    private AgentResponse execute(AgentRequest req, AgentType agentType, long deadlineNanos) {
        if (deadlineNanos - System.nanoTime() <= 0 || Thread.currentThread().isInterrupted()) {
            return executionFailure(agentType, "timeout", deadlineNanos - executionTimeoutNanos);
        }
        BaseAgent agent = bestAgent(agentType).orElseGet(() -> bestAgent(AgentType.GENERAL).orElse(null));
        if (agent == null) {
            return executionFailure(agentType, "failed", deadlineNanos - executionTimeoutNanos);
        }
        AgentResponse response = agent.handle(req);
        if (response == null || !response.success()) {
            // A failed specialist is not retried through another domain with the same model.
            // BaseAgent returns failures rather than throwing; normalize those failures too.
            String reason = deadlineNanos - System.nanoTime() <= 0 || Thread.currentThread().isInterrupted()
                    ? "timeout" : "failed";
            return executionFailure(agentType, reason, deadlineNanos - executionTimeoutNanos);
        }
        return response;
    }

    private void recordTrace(AgentRequest req, OrchestratorResult result) {
        traceStore.record(new RequestToolTrace(
                result.requestId(),
                Instant.now().toString(),
                "chat",
                req.userId(),
                req.conversationId(),
                result.intent() == null ? null : result.intent().name().toLowerCase(Locale.ROOT),
                req.intentGroup(),
                result.agentType().name().toLowerCase(Locale.ROOT),
                result.primaryAgent() == null ? null : result.primaryAgent().name().toLowerCase(Locale.ROOT),
                result.supportingAgents().stream().map(a -> a.name().toLowerCase(Locale.ROOT)).toList(),
                result.toolsUsed(),
                result.toolCalls(),
                result.toolsUsed().contains("search_knowledge_base") || result.toolsUsed().contains("knowledge_search"),
                result.escalated(),
                result.latencyMs()
        ));
    }

    private List<ToolCallTrace> collectToolCalls(List<ToolCallTrace> externalToolCalls, AgentResponse... responses) {
        List<ToolCallTrace> calls = new ArrayList<>();
        if (externalToolCalls != null) {
            calls.addAll(externalToolCalls);
        }
        if (responses != null) {
            for (AgentResponse response : responses) {
                if (response == null) {
                    continue;
                }
                if (response.toolName() != null && !response.toolName().isBlank()) {
                    calls.add(toTrace(response, response.toolName()));
                }
            }
        }
        return calls;
    }

    private List<ToolCallTrace> collectToolCalls(List<ToolCallTrace> externalToolCalls, List<AgentResponse> responses) {
        return collectToolCalls(externalToolCalls, responses == null ? null : responses.toArray(new AgentResponse[0]));
    }

    private List<String> collectToolNames(List<ToolCallTrace> externalToolCalls, AgentResponse... responses) {
        return collectToolCalls(externalToolCalls, responses).stream()
                .map(ToolCallTrace::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private List<String> collectToolNames(List<ToolCallTrace> externalToolCalls, List<AgentResponse> responses) {
        return collectToolCalls(externalToolCalls, responses).stream()
                .map(ToolCallTrace::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private ToolCallTrace toTrace(AgentResponse response, String toolName) {
        return new ToolCallTrace(
                toolName == null ? "" : toolName,
                response.success(),
                !response.success() && !toolName.startsWith("agent_execution:"),
                response.toolCached(),
                response.toolReranked(),
                response.latencyMs(),
                response.toolError() == null ? "" : response.toolError()
        );
    }

    private Optional<BaseAgent> bestAgent(AgentType agentType) {
        return pool.getOrDefault(agentType, List.of()).stream()
                .max(Comparator.comparingDouble(a -> a.stats().routingScore()));
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                BaseAgent agent = agents.get(i);
                Map<String, Object> data = new HashMap<>();
                data.put("total", agent.stats().total());
                data.put("success_rate", round(agent.stats().successRate()));
                data.put("avg_ms", round(agent.stats().avgLatencyMs()));
                data.put("monitor_penalty", round(agent.stats().monitorPenalty()));
                data.put("routing_score", round(agent.stats().routingScore()));
                result.put(type.name().toLowerCase(Locale.ROOT) + "_" + i, data);
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    public void updateRoutingPenalties(Map<String, Double> penalties) {
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                agents.get(i).stats().setMonitorPenalty(penalties.getOrDefault(type.name().toLowerCase(Locale.ROOT) + "_" + i, 0.0));
            }
        });
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record RoutingDecision(
            AgentType primaryAgent,
            List<AgentType> supportingAgents,
            String reason,
            double confidence
    ) {
        private List<AgentType> agentTypes() {
            List<AgentType> result = new ArrayList<>();
            result.add(primaryAgent);
            result.addAll(supportingAgents);
            return result;
        }

        private boolean multiAgent() {
            return !supportingAgents.isEmpty();
        }
    }
}
