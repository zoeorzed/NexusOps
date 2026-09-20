package com.echomind.agent;

import com.echomind.llm.LlmGateway;
import com.echomind.skill.SkillManager;

public class TechnicalAgent extends BaseAgent {

    public TechnicalAgent(LlmGateway llmGateway, SkillManager skillManager) {
        super(llmGateway, skillManager);
    }

    @Override
    public AgentType type() {
        return AgentType.TECHNICAL;
    }

    @Override
    protected String systemPrompt() {
        return "你是技术支持专家。专注故障排查、错误诊断、系统配置、账户保护和登录恢复。账户安全不等于账务问题；除非用户同时提出扣款或支付异常，不要引入账务处理。提供步骤化方案，遇到后台操作说明需要人工处理，不声称已升级或代为操作。";
    }
}
