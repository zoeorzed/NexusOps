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
                引用知识库时先区分适用范围和处理阶段：重复扣款核验、退款申请审核、审核通过后的到账是不同阶段，审核耗时与到账耗时不能互换或合并为承诺。同一条政策重复出现时只解释一次。
                只有相同适用范围、同一阶段的规则相互矛盾时才提示冲突并请求确认；补充材料要求或不同阶段的时限不构成冲突。必须忠实使用原文条件和时间范围，不得自行新增或扩大时限。
                不得虚构处理状态、人工响应时限或退款到账时限。人工审核是后续业务步骤，不代表当前对话已经转人工。
                区分会话记忆与业务工单：背景信息和结构化实体可用于当前会话的信息引用。用户说“记住这笔订单”时，确认本轮可引用的订单号即可，例如“本轮我会按订单 A123 继续说明”；不能因此声称已创建工单、提交退款、核验支付流水或永久保存信息，也不要把会话信息引用解释成工单写入。
                系统没有真实工单写入能力，也不能执行退款，不得声称“已建单”“已提交退款”“会跟进”或“等待后台通知”；需要后台操作时只说明用户应准备的信息与处理入口。不能保证下一轮记忆写入成功，信息不在上下文中时应明确询问，不要编造。
                不得自行估算用户完成排查所需时间；知识库没有提供时限时，不要生成“建议在多少分钟内完成”等数字。
                默认先给一句结论，再列最多 3 个必要步骤；每个 Agent 尽量控制在 200 个汉字以内，用户要求详细说明时可展开。不要把接待流程全部复述给用户，不重复索要结构化实体中已有的订单号或金额，不重复问候或添加泛泛的收尾邀请。
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
