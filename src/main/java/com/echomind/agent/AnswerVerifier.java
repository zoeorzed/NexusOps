package com.echomind.agent;

import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AnswerVerifier {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public AnswerVerifier(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public VerificationResult verify(String question, String answer, String context) {
        String prompt = """
                你是客服回答质量校验器，评估回答质量，而不是判断后台业务是否已经办结。
                pass=true 的条件：回答正确识别问题，给出与当前能力相符、可执行的下一步，并且没有虚构已经完成的后台动作。仅因问题后续可能需要人工，不能判定为不通过。
                grounded=true 的条件：涉及政策、时限或处理规则的关键事实能够被上下文支持；通用排障建议不要求逐字出现在上下文。
                平台特定事实包括密码重置后既有会话是否失效、发票更正费用和审核门槛；它们不是通用排障建议。使用“通常”“一般”也不能代替上下文依据；历史助手回答不构成政策依据。
                重复扣款争议不自动适用商品无理由退款的期限、退货条件或时限；未知发票期限不能通过假设“超期则重新申请”等分支补写。两者都需要上下文明确支持适用关系。
                回答应承接当前会话已知信息，只询问缺项；不能在材料清单中重复索要已有订单号或金额。涉及配送状态未知时，应直接澄清发货/收货状态，不能用索要订单号代替。
                need_escalation=true 只表示当前轮次必须立即升级人工。回答中“若自助排查无效，再联系人工”“可能需要人工后台排查”等条件性说明，不属于当前立即升级，应返回 false。
                如果用户没有明确要求人工，回答仍提供了可执行的自助步骤或需要补充的信息，通常 need_escalation=false。
                用户问题: %s
                回答: %s
                上下文: %s
                返回 JSON: {"pass":true,"grounded":true,"need_escalation":false,"reason":"..."}
                """.formatted(question, answer, context == null ? "" : context);
        try {
            String raw = llmGateway.chat("", prompt, 0.0, 256);
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            Map<String, Object> data = objectMapper.readValue(raw.substring(start, end + 1), new TypeReference<>() {
            });
            boolean judgeEscalation = Boolean.TRUE.equals(data.get("need_escalation"));
            return new VerificationResult(
                    Boolean.TRUE.equals(data.get("pass")),
                    Boolean.TRUE.equals(data.get("grounded")),
                    normalizeEscalation(question, answer, judgeEscalation),
                    String.valueOf(data.getOrDefault("reason", ""))
            );
        } catch (Exception ex) {
            return new VerificationResult(false, false, normalizeEscalation(question, answer, false), "verifier unavailable: answer unverified");
        }
    }

    private boolean normalizeEscalation(String question, String answer, boolean judgeEscalation) {
        String userText = question == null ? "" : question.toLowerCase();
        String answerText = answer == null ? "" : answer.toLowerCase();
        boolean userRequestedHuman = containsAny(userText, "转人工", "人工客服", "真人客服", "人工处理");
        boolean immediateInstruction = containsAny(answerText,
                "请立即联系人工客服",
                "必须立即联系人工",
                "当前无法继续处理",
                "当前必须升级人工"
        );
        if (userRequestedHuman || immediateInstruction) {
            return true;
        }
        boolean conditionalOnly = containsAny(answerText,
                "若以上步骤",
                "如果以上步骤",
                "若仍",
                "如果仍",
                "后续联系人工",
                "可能需要人工"
        );
        return judgeEscalation && !conditionalOnly;
    }

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    public record VerificationResult(boolean pass, boolean grounded, boolean needEscalation, String reason) {
    }
}
