# 回答质量收尾记录（2026-09-21）

本轮修复上一轮业务复核发现的资料外推断、已知信息重复收集、配送状态澄清不足和内部协同措辞，并在真实复测中继续修正日期误归及退款政策适用范围问题。最终仍使用原来的 12 条合成业务请求、8 篇参考资料和 137 项结构检查；没有改题来提高结果。

## 修改内容

- **区分沟通规则与政策事实。** 精简三个应用 Skills，移除其中自带的跨月发票审核、概率性故障判断等经验规则；统一规则放在动态 Skills 之后。资料未给出的期限、费用、审核门槛或账户会话机制明确说明未知，也不能用“若超期则……”补写假设性规则。
- **区分重复扣款与普通退货退款。** 没有明确知识依据时，不把商品七天无理由退款窗口、先退货条件及其时限套到多扣金额的争议处理。审核与到账分别说明起算点，不说“不能合并计算”。
- **承接当前会话信息。** 先引用已知订单和金额，仅询问影响下一步的缺项；材料清单也不能重复索要已知字段。没有订单信息时不得暗示本会话已经提供。
- **缩短并澄清下一步。** 一句能答完就一句，不设最低字数，也不要求凑满三条。配送退款先澄清发货与收货状态；下单遇到 500 先核对是否成功，状态不明不重复提交。
- **修复领域日期误绑定。** 技术与账务子任务不再共享所有日期，仅注入本领域独有子句明确出现的日期。避免把“昨天多扣”解释成“昨天开始闪退”。这仍是有限关键词与子句规则，不是完整的事实归属解析器。
- **改进用户可见输出。** 复合回答使用“技术问题、账务问题”等标题，繁忙、失败及超时提示也使用业务名称；结构化主辅路由与 Trace 保留。移除末段关键词清洗，避免误删含 `User-Agent` 等合法术语的完整回答。每领域仍只生成一次，没有新增模型润色调用。

## 验证结果

| 检查 | 最终结果 | 证据 |
| --- | --- | --- |
| Java 干净构建、测试、打包 | 76 项通过，0 失败、错误或跳过；含真实 Redis 集成测试 | [验证摘要](../evaluation/results/2026-09-21-answer-quality/verification-summary.json) |
| 原有 Python 辅助工具测试 | 25 项通过 | 检查采集器与统计工具，未增加 Python 后端逻辑 |
| 真实模型业务请求 | 12 条，137/137 结构检查通过 | [最终原始回答与 Trace](../evaluation/results/2026-09-21-answer-quality/third-business.json) |
| 独立检索回归 | 8 条有答案题均首位命中；2 条无答案题单列 | 同上，不能代表回答逐句引用正确率 |
| 内容复核 | 原有重点问题及前两轮新发现的实质问题，在最终样本中未再复现 | [12 条内容复核](../evaluation/results/2026-09-21-answer-quality/third-content-review.json) |
| 受控并发回归 | 172 请求、15 项行为检查通过；部分失败仍保留成功回答，同池恢复 40/40 | [逐请求结果](../evaluation/results/2026-09-21-answer-quality/load-test-result.json)、[独立重算](../evaluation/results/2026-09-21-answer-quality/load-independent-recalculation.json) |

Java 回归补充了完整多领域正文保留、模型调用次数、失败与成功内容聚合、中文故障提示，以及日期跨领域隔离。未修改线程池容量、统一截止时间、拒绝策略或路由评分规则。

内容复核由另一个 AI 助手任务独立阅读完整回答与冻结资料完成，再由主任务复核。它不是人工盲评，不产生“问答准确率”；`verified`/`grounded` 也不作为验收真值。最终仍有两处非阻断体验项：同会话追问列了普通退款与争议两种分支并再次确认意图；缺失发票政策的回答额外解释了退款时限不适用。这些可以继续精简，但最终没有再把两类退款规则混为一谈，也未编造发票期限。

## 保留失败与迭代证据

| 轮次 | 内容观察 |
| --- | --- |
| 第一轮 | 主要旧问题改善，但发现扣款日期误归闪退、“不能合并计算”的歧义及 500 重试顺序冲突；部分回答仍长 |
| 第二轮 | 日期、阶段说明和重试顺序改善，篇幅缩短；仍发现重复扣款套普通退款条件、未知发票期限后补写超期分支 |
| 第三轮 | 上述实质问题未再复现，保留两处非阻断表达改进项 |

三轮原始响应、元数据与逐条审查全部保留：[第一轮](../evaluation/results/2026-09-21-answer-quality/first-business.json) / [审查](../evaluation/results/2026-09-21-answer-quality/first-content-review.json)，[第二轮](../evaluation/results/2026-09-21-answer-quality/second-business.json) / [审查](../evaluation/results/2026-09-21-answer-quality/second-content-review.json)，[第三轮](../evaluation/results/2026-09-21-answer-quality/third-business.json) / [审查](../evaluation/results/2026-09-21-answer-quality/third-content-review.json)。没有删除失败案例或只选成功回答。

例如本轮第一版到最终版，闪退与扣款复合回答由 561 缩短至 182 字符，账户安全回答由 389 至 116 字符，500 回答由 291 至 112 字符。这里按完整字符串的 Unicode 码点计数，包括 Markdown、标点和空白；不是汉字数、token 数或质量得分。全部样本及上一轮基线的比较见 [长度记录](../evaluation/results/2026-09-21-answer-quality/answer-length-comparison.json)。不同轮次为不同模型生成，不能视作生产环境的严格因果实验。

## 构建与复现边界

最终实测使用 Windows、JDK 25.0.2（release 21）、Maven 3.9.9、隔离 Redis 3.2.100，以及 `deepseek-chat` 模型别名；关闭模型 fallback，每轮使用空业务数据目录和新合成会话。最终采集开始于北京时间 2026-09-21 01:42:16。模型别名不是不可变版本，未来生成措辞和长度可能变化。

最终 JAR SHA-256 为：

```text
5f146c5dd0eb02588ac2d814d89dfb886c7f5c7c770224f51328f9823012fee1
```

代码基于提交 `de51d31088f9c63a2c3c116ecdd34f22ac92b120`，最终源码由包含本文的提交保存。[最终元数据](../evaluation/results/2026-09-21-answer-quality/third-metadata.json) 记录 JAR、四个 Java 文件、三份 Skills、数据集、采集器、演示知识和 [本轮审查清单](../evaluation/answer-quality-checklist-v1.json) 的指纹。第一、二轮是中间版本证据，其源码指纹不应当作最终版本。

原 12 条业务数据、采集器及 95 条意图候选集和 `IntentRecognizer` 未改。本轮没有重新跑 95 条真实模型意图评测。受控并发使用模型替身，只验证编排机制回归，不代表真实模型吞吐或整个 HTTP 链路延迟。

复现步骤沿用 [业务验证说明](business-validation.md) 和 [并发说明](load-testing.md)。Java 构建使用 `./scripts/verify.ps1 -RedisPort <专用端口>`；业务采集使用新输出路径，另按本轮清单逐条复核内容。普通 CI 运行 Java、辅助 Python 和受控并发测试，不调用真实模型；具体提交的运行状态见 [Java CI](https://github.com/zoeorzed/NexusOps/actions/workflows/ci.yml)。
