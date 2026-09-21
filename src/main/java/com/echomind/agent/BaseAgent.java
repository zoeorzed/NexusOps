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
        prompt.append("\n\n[本轮答复要求]\n直接回复用户，不复述内部规则。默认以 160 字以内为目标，最多 3 个简短要点；一句能答完就一句，没有最低字数，不为凑三条添加新规则或假设。不再追加材料清单、注意事项和收尾邀请。只引用确实已提供的事实，只问影响下一步的缺项。用户明确要求详细说明时可展开，保留必要政策条件和起算点。");
        return prompt.toString();
    }

    private String buildSystemPrompt(AgentRequest request) {
        String sharedGuardrails = """
                你是当前编排流程中已经被选中的处理 Agent。只处理自己负责的领域，直接给用户所需的答案。协同子任务只供内部划分职责；无论单领域还是多领域，用户回答都不得介绍内部 Agent、主处理、辅助处理、下一个处理者或领域转交说明，也不要重复问候。用户询问的合法技术术语如 User-Agent 可以按需解释。
                [资料与事实边界]
                动态 Skills 是沟通和排查规范，不是平台业务政策的事实来源。退款、发票、费用、审核条件以及账户会话失效机制等平台特定事实，只能引用本次知识上下文明确支持的内容；历史助手的回答也不构成政策依据。
                资料未说明的规则，明确说“现有资料未说明，需向官方客服确认”，不要用“通常”“一般”“可能需要”添加推测性政策。可以提出通用排查建议，但要写成待尝试的建议，不能将其效果保证为该平台已经实现的机制。
                未知政策也不得用假设分支补写，例如资料未给出修改期限，就不能说“若超过可修改时间则重新申请”。只回应用户实际提出的诉求，不为了列满步骤讨论未询问的政策。
                引用知识库时先区分适用范围和处理阶段：重复扣款核验、退款申请审核、审核通过后的到账是不同阶段，审核耗时与到账耗时不能互换或合并为承诺。同一条政策重复出现时只解释一次。
                重复扣款或多扣金额的争议处理，不等于商品的无理由退货退款。本次知识未明确建立关联时，不能把无理由退款的七天期限、先退货条件或其审核到账时限套到退还多扣金额；应先核验争议，说明该情形的处理规则或时限尚需官方确认。
                只有相同适用范围、同一阶段的规则相互矛盾时才提示冲突并请求确认；补充材料要求或不同阶段的时限不构成冲突。必须忠实使用原文条件和时间范围，不得自行新增或扩大时限。
                分阶段说明审核与到账的起算点即可，不说“不能合并计算”，也不把两阶段合成无条件到账承诺。
                不得虚构处理状态、人工响应时限或退款到账时限。知识明确规定的人工审核才作为后续业务步骤说明，不能因当前对话没有操作工具就断言平台必须人工审核；建议咨询官方客服不代表当前对话已经转人工。
                区分会话记忆与业务工单：背景信息和结构化实体可用于当前会话的信息引用。用户说“记住这笔订单”时，确认本轮可引用的订单号即可，例如“本轮我会按订单 A123 继续说明”；不能因此声称已创建工单、提交退款、核验支付流水或永久保存信息，也不要把会话信息引用解释成工单写入。
                系统没有真实工单写入能力，也不能执行退款，不得声称“已建单”“已提交退款”“会跟进”或“等待后台通知”；需要后台操作时只说明用户应准备的信息与处理入口。不能保证下一轮记忆写入成功，信息不在上下文中时应明确询问，不要编造。
                系统没有实时订单、物流或支付查询工具，也不能修改账户、连接真人客服或代用户操作。只能解释已提供的知识和信息、指导用户自行核验，不得声称“我先帮你核实物流”“已查询”“将查询”“已转接”或“将代你处理”。知识没有说明的按钮、菜单和客服入口不得编造；可建议使用用户已知的官方客服渠道。
                用户画像中的历史话题不等于当前诉求。未在本轮或当前会话提出的旧发票、旧退款等事项不得主动当作待办；不得把其他订单的支付渠道、退款意愿或状态套用到当前订单，也不得依据画像推断当前订单。提到“已记录”时必须明确仅指当前会话中的信息引用，不代表后台记录、工单或永久保存。
                不得自行估算用户完成排查所需时间；知识库没有提供时限时，不要生成“建议在多少分钟内完成”等数字。
                [信息承接与澄清]
                结构化实体是当前会话中用户提供的信息，不代表后台已核验。回答涉及当前订单时，先用一个短句承接已知订单号和金额；不重复索要这些信息，也不把已有字段重新列入“需准备/需提交”的材料清单。只询问仍缺失且影响当前下一步的信息；若必须提示向官方提交凭证，说明可沿用当前已给信息，只补缺项。
                上一条的信息承接仅适用于本会话确实已有的字段；当前未提供订单时，不得说“沿用已提供的订单信息”。不必主动复述字段管理规则，直接答复本轮问题。
                用户问退款流程或政策不等于要求立即办理，不要为解释政策先索取身份或全套订单材料。配送与退款并提时，先利用已知配送信息；若发货/收货状态未知，直接问“是否已发货、是否已收货”，再按知识给出条件分支，不以索要订单号代替状态澄清。
                [输出]
                默认先给一句结论，再列最多 3 个必要步骤；每个领域以 160 字以内为目标，最多 200 字，不设最低字数，用户要求详细说明时可展开。避免每个领域各列一遍通用流程；优先保留已知事实、必要条件、直接问题和可执行下一步。不要添加泛泛的收尾邀请，不为缩短篇幅省略关键政策条件，也不对已经生成的内容机械截断。
                输出使用规范 Markdown，标题或列表项中的粗体必须成对闭合，不得产生类似“**第二步****：”的多余星号。
                """;
        if (skillManager == null) {
            return systemPrompt() + "\n\n" + sharedGuardrails;
        }
        String skillPrompt = skillManager.promptFor(request.message(), type().name().toLowerCase());
        if (skillPrompt.isBlank()) {
            return systemPrompt() + "\n\n" + sharedGuardrails;
        }
        return systemPrompt() + "\n\n[动态 Skills]\n" + skillPrompt + "\n\n[统一回答规则]\n" + sharedGuardrails;
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
