package com.echomind.agent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class EvidenceBoundaryChecksTest {
    @org.junit.jupiter.api.Test void knownRecoveryMethodIsNotAnExclusiveRule() {
        String knowledge = "忘记密码可通过绑定手机号或邮箱重置";
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("号码没有绑定", "既然未绑定当前账号，就不能用它重置密码。", knowledge)).contains("排他");
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("号码不确定是否绑定", "如果绑定可以重置；若未绑定，则无法恢复。", knowledge)).contains("排他");
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("号码未绑定", "资料未说明未绑定号码的恢复规则，请向官方确认。", knowledge)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("号码未绑定", "未绑定号码不能恢复。", "只能通过绑定手机号恢复；未绑定号码不能恢复。")).isEmpty();
    }
    @org.junit.jupiter.api.Test void missingFieldsDoNotMeanMissingStorageCapability() {
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("有单号吗", "我也没有后台存储或工单写入能力。", "")).contains("存储");
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("有单号吗", "本会话尚未提供订单号，无法引用。", "")).isEmpty();
    }
    @org.junit.jupiter.api.Test void rejectingANegativeCapabilityClaimIsNotThatClaim() {
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("有单号吗", "我没有收到单号，这不代表我没有后台存储能力。", "")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("有单号吗", "字段缺失不代表没有记忆。我也没有后台存储能力。", "")).contains("存储");
    }
    @org.junit.jupiter.api.Test void usablePhoneDoesNotProveAccountBinding() {
        String knowledge = "忘记密码可通过绑定手机号或邮箱重置";
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("手机号码还能用但忘了密码", "可以。手机号仍可用即符合这一方式。", knowledge)).contains("已绑定");
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("手机号码没有绑定，忘了密码", "可以。", knowledge)).contains("已绑定");
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("绑定手机号还能用，忘了密码", "可以。可通过绑定手机号重置。", knowledge)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(EvidenceBoundaryChecks.check("手机号码还能用但忘了密码", "如果该号码已绑定当前账户，可通过它重置密码。", knowledge)).isEmpty();
    }
 @Test void locationCannotBeDeniedAndUnknownAtOnce() {
  assertThat(EvidenceBoundaryChecks.check("是不是在设置里？","不是。资料未说明入口，无法确认。","")).contains("相矛盾");
  assertThat(EvidenceBoundaryChecks.check("六位密码够吗？","不是。至少八位。","")).isEmpty();
  assertThat(EvidenceBoundaryChecks.check("是不是设置里？","资料未说明，无法确认是否在设置里。","")).isEmpty();
 }
 @Test void observedPageAndDocumentedStepsAreNotBlanketRejected() {
  assertThat(EvidenceBoundaryChecks.check("我看到登录页有找回密码。","可在登录页按你看到的提示尝试。","")).isEmpty();
  assertThat(EvidenceBoundaryChecks.check("怎么重置","在重置流程中选择邮箱验证。","说明：在重置流程中选择邮箱验证。")).isEmpty();
  assertThat(EvidenceBoundaryChecks.check("怎么重置","在重置流程中选择邮箱验证。","通过绑定邮箱重置")).contains("验证选项");
 }
 @Test void missingOrdersDifferFromKnownUserFields() {
  assertThat(EvidenceBoundaryChecks.check("多扣一笔怎么办","沿用你已提供的订单信息。","需要核对订单号")).contains("没有对应订单字段");
  assertThat(EvidenceBoundaryChecks.check("下一步？","沿用你已提供的订单信息。","user: 订单A123，金额29元")).isEmpty();
 }
 @Test void adviceCannotInventPageButUnknownStatementCanMentionIt() {
  assertThat(EvidenceBoundaryChecks.check("在哪","现有资料未说明入口，建议以你登录页实际看到的渠道为准。","")).contains("页面位置");
  assertThat(EvidenceBoundaryChecks.check("在哪","无法确认是否在登录页。","")).isEmpty();
 }
 @Test void unknownApplicabilityIsNotExclusion() {
  assertThat(EvidenceBoundaryChecks.check("重复扣款","普通退款规则不适用于该争议。","普通规则不能自动套用")).contains("排除适用");
  assertThat(EvidenceBoundaryChecks.check("重复扣款","不能说普通退款规则不适用于该争议。","普通规则不能自动套用")).isEmpty();
 }
 @Test void assistantHistoryDoesNotAuthorizeNewUiSteps() {
  assertThat(EvidenceBoundaryChecks.check("继续","在重置流程中选择邮箱验证。","[最近对话]\nassistant: 在重置流程中选择邮箱验证。\n[知识库检索结果]\n仅支持邮箱重置")).contains("验证选项");
 }
}
