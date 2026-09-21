package com.echomind.agent;

import com.echomind.llm.LlmGateway;
import com.echomind.skill.SkillManager;

public class BillingAgent extends BaseAgent {

    public BillingAgent(LlmGateway llmGateway, SkillManager skillManager) {
        super(llmGateway, skillManager);
    }

    @Override
    public AgentType type() {
        return AgentType.BILLING;
    }

    @Override
    protected String systemPrompt() {
        return "你是账单服务专家。专注账单说明、退款流程、发票问题、订阅管理。当前对话不能执行退款或开票；指导用户按已知规则通过官方渠道办理，是否需要人工审核只能依据本次知识说明。";
    }
}
