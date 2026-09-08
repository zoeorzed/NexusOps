package com.echomind.agent;

import com.echomind.llm.LlmGateway;
import com.echomind.skill.SkillManager;
import com.echomind.trace.ToolCallTrace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public abstract class BaseAgent {

    private final LlmGateway llmGateway;
    private final SkillManager skillManager;
    private final AgentStats stats = new AgentStats();

    protected BaseAgent(LlmGateway llmGateway, SkillManager skillManager) {
        this.llmGateway = llmGateway;
        this.skillManager = skillManager;
    }

    public abstract AgentType type();

    protected abstract String systemPrompt();

    public AgentResponse handle(AgentRequest request) {
        Instant start = Instant.now();
        try {
            String prompt = buildPrompt(request);
            String content = normalizeMarkdown(llmGateway.chat(buildSystemPrompt(request), prompt, 0.2, 1024));
            long latency = Duration.between(start, Instant.now()).toMillis();
            boolean escalate = needsEscalation(content);
            stats.record(true, latency);
            return new AgentResponse(type(), content, true, 1.0, latency, escalate, "", true, false, false, "");
        } catch (Exception ex) {
            long latency = Duration.between(start, Instant.now()).toMillis();
            stats.record(false, latency);
            return new AgentResponse(type(), "抱歉，处理您的请求时出现问题，请稍后重试。", false, 0.0, latency, false, "", false, false, false, ex.getMessage());
        }
    }

    public AgentStats stats() {
        return stats;
    }

    private String buildPrompt(AgentRequest request) {
        StringBuilder prompt = new StringBuilder();
        if (request.context() != null && !request.context().isBlank()) {
            prompt.append("[背景信息]\n").append(request.context()).append("\n\n");
        }
        if (request.entities() != null && !request.entities().isEmpty()) {
            prompt.append("[结构化实体]\n").append(request.entities()).append("\n\n");
        }
        prompt.append("[用户问题]\n").append(request.message());
        return prompt.toString();
    }

    private String buildSystemPrompt(AgentRequest request) {
        String sharedGuardrails = """
                你是当前编排流程中已经被选中的处理 Agent。只处理自己负责的领域；如果请求包含其他领域，说明该部分由协同 Agent 处理，不要声称需要再次转交给与自己相同类型的 Agent。
                引用知识库时必须忠实使用原文条件和时间范围；若知识内容冲突，应明确指出冲突并请求确认适用规则，不得自行拼接或扩大时间范围。
                不得虚构处理状态、人工响应时限或退款到账时限。人工审核是后续业务步骤，不代表当前对话已经转人工。
                系统没有真实工单写入能力时，不得声称“已记录”“将记录”“已提交”“将提交”“会跟进”或“等待后台通知”；只能说明建议用户准备哪些信息，以及何种情况需要人工后台排查。
                不得自行估算用户完成排查所需时间；知识库没有提供时限时，不要生成“建议在多少分钟内完成”等数字。
                输出使用规范 Markdown，标题或列表项中的粗体必须成对闭合，不得产生类似“**第二步****：”的多余星号。
                """;
        if (skillManager == null) {
            return systemPrompt() + "\n\n" + sharedGuardrails;
        }
        String skillPrompt = skillManager.promptFor(request.message(), type().name().toLowerCase());
        if (skillPrompt.isBlank()) {
            return systemPrompt() + "\n\n" + sharedGuardrails;
        }
        return systemPrompt() + "\n\n" + sharedGuardrails + "\n\n[动态 Skills]\n" + skillPrompt;
    }

    private boolean needsEscalation(String content) {
        String text = content == null ? "" : content.toLowerCase();
        return text.contains("请立即联系人工客服")
                || text.contains("必须立即联系人工")
                || text.contains("当前必须升级人工")
                || text.contains("当前无法继续处理")
                || text.contains("escalate")
                || text.contains("specialist");
    }

    private String normalizeMarkdown(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceAll("\\*\\*([^*\\r\\n]+)\\*\\*\\*\\*([：:])", "**$1**$2");
    }
}
