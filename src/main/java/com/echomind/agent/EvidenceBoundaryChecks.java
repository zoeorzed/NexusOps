package com.echomind.agent;

import java.util.regex.Pattern;

/** Narrow, conservative checks for documented failure modes; not a semantic proof system. */
final class EvidenceBoundaryChecks {
    private EvidenceBoundaryChecks() {}
    static String check(String question, String answer, String context) {
        if (answer == null) return "";
        String q = question == null ? "" : question;
        String c = context == null ? "" : context;
        String knowledge = c.contains("[知识库检索结果]") ? c.substring(c.indexOf("[知识库检索结果]"))
                : (c.contains("[最近对话]") || c.contains("[会话摘要]") ? "" : c);
        String userFacts = q + "\n" + c.lines().filter(line -> line.startsWith("user:") || line.contains("当前会话用户："))
                .reduce("", (a,b) -> a + "\n" + b);
        String plain = answer.replace("**", "").strip();
        for (String sentence : plain.split("[。！？；\\n]")) {
            if (!matches(sentence, "不代表|不等于|并非|不能说|不是没有")
                    && matches(sentence, "(我|本系统|本对话|本会话).{0,15}(没有|不具备|无法).{0,8}(后台存储|会话存储|记忆能力|长期保存)")) {
                return "boundary: 系统具备会话记忆与存储；未收到字段不等于没有存储能力。请只说明本会话尚未提供对应信息，不追加存储能力结论";
            }
        }
        if (matches(knowledge, "绑定手机号|绑定手机号码")
                && !matches(knowledge, "只能通过|未绑定.{0,25}(不能|无法)")
                && !matches(plain, "不能.{0,8}断言|不等于|不意味着")
                && (matches(plain, "未绑定.{0,35}(不能|无法)(?!确认|判断|确定|据此|推断)|只能通过.{0,30}绑定")
                    || (matches(userFacts, "未绑定|没有绑定|没绑定") && matches(plain, "不能用.{0,8}重置|无法用.{0,8}重置")))) {
            return "boundary: 知识说明可通过绑定方式重置，不证明该方式排他；只说明资料未提供未绑定号码或其他方式的恢复规则，不能断言无法或只能";
        }
        if (matches(knowledge, "绑定手机号|绑定手机号码")
                && matches(userFacts, "手机号|手机号码") && matches(userFacts, "忘.{0,4}密码|恢复|重置")
                && (!matches(userFacts, "绑定.{0,8}(手机|号码)|(手机|号码).{0,8}绑定")
                    || matches(userFacts, "没.{0,3}绑定|未绑定|没有绑定"))
                && (matches(plain, "^(可以|能|是的)[。！!，,]")
                    || matches(plain, "手机号.{0,8}可用.{0,8}(符合|可用|可以|能)"))
                && !matches(plain, "(如果|若|前提|需要先确认).{0,30}绑定")) {
            return "boundary: 手机号可用不证明已绑定当前账户；应先说明已绑定这一条件，不能直接确认可恢复";
        }
        if (matches(q, "入口|页面|设置|中心|头像")
                && matches(plain, "^(不是|是|是的|不对|对|没错)[。！!]" )
                && matches(plain, "无法确认|无法判断|未说明|不清楚")) {
            return "boundary: 确定的位置判断与随后无法确认相矛盾；资料未知不支持是或不是";
        }
        if (matches(plain, "你已提供的订单|你已经提供的订单|沿用.{0,8}已提供的订单")
                && !matches(userFacts, "[A-Za-z][A-Za-z0-9_-]*[0-9][A-Za-z0-9_-]*|[0-9]+(?:\\.[0-9]+)?\\s*元")) {
            return "boundary: 当前用户及本会话没有对应订单字段，不能称已提供订单信息";
        }
        for (String sentence : plain.split("[。！？；\\n]")) {
            if (sentence.isBlank()) continue;
            boolean unknown = matches(sentence, "未说明|无法确认|无法判断|不清楚|没有说明|未给出");
            boolean instruction = matches(sentence, "建议|查找|以.{0,40}为准");
            if (matches(sentence, "登录页|设置[→＞>]|账户安全相关页面") && (!unknown || instruction)
                    && !matches(knowledge, "登录页|设置[→＞>]|账户安全相关页面")
                    && !(matches(userFacts, "看到|看见") && matches(userFacts, "登录页|设置[→＞>]|账户安全相关页面"))) {
                return "boundary: 页面位置未获知识或用户观察支持，不能补写位置或建议去该位置查找";
            }
            if (!unknown && matches(sentence, "选择.{0,8}(邮箱|手机).{0,4}验证|按邮件.{0,6}(提示|设置)")
                    && !matches(knowledge, "选择.{0,8}(邮箱|手机).{0,4}验证|按邮件.{0,6}(提示|设置)")) {
                return "boundary: 恢复方式不支持额外的验证选项或邮件操作步骤，应只保留已知恢复方式";
            }
            if (!unknown && matches(sentence, "解锁.{0,8}(需|必须).{0,8}(客服|人工)|(需|必须).{0,8}(客服|人工).{0,8}解锁")
                    && !matches(knowledge, "解锁")) {
                return "boundary: 知识未提供解锁权限规则，不能断言必须由客服解锁";
            }
            if (!unknown && matches(sentence, "按钮.{0,60}(一致|对应)") && !matches(knowledge, "按钮")) {
                return "boundary: 用户观察到按钮不证明它与某个恢复功能对应";
            }
            if (matches(knowledge, "不能自动套用") && matches(sentence, "不适用于.{0,12}(争议|重复扣款)|不走普通退款流程")
                    && !matches(sentence, "不等于|不代表|不能说|不能直接说|无法确认|未说明|未确认|不能认定|不能据此")) {
                return "boundary: 未确认适用关系不等于排除适用，不能确定说不适用";
            }
        }
        return "";
    }
    private static boolean matches(String value, String regex) {
        return Pattern.compile(regex).matcher(value).find();
    }
}
