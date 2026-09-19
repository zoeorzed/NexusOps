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
}
