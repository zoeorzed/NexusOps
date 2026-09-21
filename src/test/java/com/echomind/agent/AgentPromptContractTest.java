package com.echomind.agent;

import com.echomind.config.EchoMindProperties;
import com.echomind.llm.LlmGateway;
import com.echomind.skill.SkillManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies actual model inputs, not the quality of generated answers. */
class AgentPromptContractTest {
    @Test
    void knownOrderReachesModelAlongsideConversationMemoryBoundary() {
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();
        LlmGateway capture = (s, p, t, m) -> { system.set(s); user.set(p); return "capture only"; };
        AgentRequest request = new AgentRequest(
                "那这笔订单怎么退款？", "demo", "conversation", "当前会话订单 A20260914002", List.of(),
                Map.of("order_id", List.of("A20260914002"), "amount", List.of("299元")),
                null, null, null, 1.0, "request");
        new BillingAgent(capture, null).handle(request);

        assertThat(user.get()).contains("[结构化实体]", "A20260914002", "299元", "那这笔订单怎么退款？");
        assertThat(system.get()).contains("区分会话记忆与业务工单", "当前会话的信息引用", "永久保存信息",
                "系统没有真实工单写入能力");
        assertThat(system.get()).doesNotContain("不得声称“已记录”“将记录”");
    }

    @Test
    void billingPromptSeparatesPolicyStagesAndLoadsUpdatedBusinessSkill() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getSkills().setRootDir("skills");
        SkillManager manager = new SkillManager(properties, new ObjectMapper());
        manager.load();
        AtomicReference<String> captured = new AtomicReference<>();
        LlmGateway capture = (s, p, t, m) -> { captured.set(s); return "capture only"; };
        new BillingAgent(capture, manager).handle(
                AgentRequest.of("重复扣款，退款多久到账？", "demo", "c", "", List.of()));

        assertThat(captured.get()).contains("[动态 Skills]", "审核耗时与到账耗时不能互换",
                "相同适用范围、同一阶段", "没有核验时限就不新增数字", "重复政策只解释一次");
    }

    @Test
    void conciseDefaultDoesNotTruncateRequestedDetailedAnswers() {
        AtomicReference<String> captured = new AtomicReference<>();
        String detailedAnswer = "详细步骤。".repeat(100);
        LlmGateway capture = (s, p, t, m) -> { captured.set(s); return detailedAnswer; };
        List<BaseAgent> agents = List.of(new GeneralAgent(capture, null),
                new TechnicalAgent(capture, null), new BillingAgent(capture, null));
        for (BaseAgent agent : agents) {
            AgentResponse result = agent.handle(AgentRequest.of("请详细说明", "demo", "c", "", List.of()));
            assertThat(captured.get()).contains("最多 3 个必要步骤", "用户要求详细说明时可展开", "不重复索要");
            assertThat(result.content()).isEqualTo(detailedAnswer);
        }
    }

    @Test
    void allAgentsReceiveCapabilityAndHistoricalProfileBoundaries() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getSkills().setRootDir("skills");
        SkillManager manager = new SkillManager(properties, new ObjectMapper());
        manager.load();
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> prompt = new AtomicReference<>();
        LlmGateway capture = (s, p, t, m) -> {
            system.set(s);
            prompt.set(p);
            return "capture only";
        };
        AgentRequest request = AgentRequest.of("查询本次订单物流", "demo", "new-conversation",
                "[用户画像]历史话题：旧订单退款到银行卡、补发票", List.of());
        for (BaseAgent agent : List.of(new GeneralAgent(capture, manager),
                new TechnicalAgent(capture, manager), new BillingAgent(capture, manager))) {
            agent.handle(request);
            assertThat(prompt.get()).contains("旧订单退款到银行卡", "查询本次订单物流");
            assertThat(system.get()).contains("系统没有真实工单写入能力，也不能执行退款",
                    "没有实时订单、物流或支付查询工具", "不能修改账户、连接真人客服或代用户操作",
                    "按钮、菜单和客服入口不得编造", "用户画像中的历史话题不等于当前诉求",
                    "不得把其他订单的支付渠道、退款意愿或状态套用到当前订单",
                    "仅指当前会话中的信息引用", "协同子任务只供内部划分职责",
                    "无论单领域还是多领域，用户回答都不得介绍内部 Agent");
        }
    }

    @Test
    void accountSecurityPromptIncludesProtectionAndLoginRecoverySkill() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getSkills().setRootDir("skills");
        SkillManager manager = new SkillManager(properties, new ObjectMapper());
        manager.load();
        AtomicReference<String> captured = new AtomicReference<>();
        LlmGateway capture = (s, p, t, m) -> { captured.set(s); return "capture only"; };

        new TechnicalAgent(capture, manager).handle(
                AgentRequest.of("账号被盗，登录不进去", "demo", "c", "", List.of()));

        assertThat(captured.get()).contains("账户保护和登录恢复", "账户安全不等于账务问题",
                "[动态 Skills]", "不能修改密码、冻结账户、踢出会话或查询登录记录");
    }

    @Test
    void finalGuardrailsOverrideSkillChecklistsAndUnverifiedPlatformAssumptions() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getSkills().setRootDir("skills");
        SkillManager manager = new SkillManager(properties, new ObjectMapper());
        manager.load();
        AtomicReference<String> captured = new AtomicReference<>();
        LlmGateway capture = (s, p, t, m) -> { captured.set(s); return "capture only"; };
        new BillingAgent(capture, manager).handle(
                AgentRequest.of("跨月发票更正收费吗？订单没收到也想退款", "demo", "c", "", List.of()));
        String system = captured.get();
        assertThat(system.indexOf("[统一回答规则]")).isGreaterThan(system.indexOf("[动态 Skills]"));
        assertThat(system).contains("不是平台业务政策的事实来源", "是否已发货、是否已收货",
                "不把已有字段重新列入", "解释政策或流程时不要求先提供全套核验材料");
        assertThat(system).doesNotContain("尤其是跨月或已报销场景", "涉及资金处理必须提示需要订单号");

        new TechnicalAgent(capture, manager).handle(
                AgentRequest.of("异地登录，重置密码能踢掉陌生设备吗", "demo", "c", "", List.of()));
        assertThat(captured.get()).contains("知识未说明时必须明确无法确认", "不构成政策依据");
    }
}
