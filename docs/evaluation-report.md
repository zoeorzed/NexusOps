# NexusOps Java 评测报告

2026-09-19 新运行结果见 [完整验证记录](verification-2026-09-19.md)：同一批 95 条合成候选，真实 DeepSeek 93/95、Macro-F1 0.9787，离线生产降级 50/95、Macro-F1 0.4876。完整逐题 JSON 分别保存，历史小样本成绩继续保留在下方。

## 数据版本与证据边界（2026-09-19 更新）

本报告下方的 2026-09-04 数字来自原仓库历史记录；它们属于 **12 条意图 + 5 轮对话**的旧冒烟集，不是 19 类全覆盖或 95 条候选集成绩。本轮保留原文件，并新增 `evaluation/legacy-smoke-v1.json` 冻结副本，避免旧成绩换数据集后继续沿用。

新增 [`intent-holdout-candidate-v1.json`](../evaluation/intent-holdout-candidate-v1.json) 覆盖 19 类、每类 5 条，共 95 条；是 AI 辅助编写的合成候选，未经独立人工盲审，不代表真实业务流量。候选没有用于修改生产分类器；后续若据此调参，这版应降为开发/回归集。数据边界、标签规则和复现命令见 [`evaluation/README.md`](../evaluation/README.md)。

本轮评测分为 `offline_production_intent_fallback` 与 `application_pipeline` 两种模式。前者对实际 `IntentRecognizer` 注入必失败网关，完全不使用真实模型/密钥；后者调用应用配置的模型，须同时检查 `intent_llm_failure_count` 和 `intent_failure_count`，不能把降级成绩称为真实模型成绩。结果应以对应运行生成的完整 JSON 为准，不能将历史表格当成本轮重跑证明。

当前对话评测是 **AgentOrchestrator + Judge**，绕过 `/chat` Controller 的知识检索、Redis 记忆及历史实体补全；它不验证完整 `/chat` 链路。LLM Judge 的 accuracy 是模型的主观评分，没有检索证据或人工参考答案时不能视为事实正确率。

## 评测口径

- 历史数据集：[`evaluation/eval-dataset.json`](../evaluation/eval-dataset.json)；脚本现默认使用上述 95 条候选集，显式选择旧文件才复跑历史范围。
- 意图指标：Accuracy、Macro-F1、每类 Precision / Recall / F1
- 对话指标：LLM-as-Judge 对 relevance、accuracy、completeness、helpfulness 四维评分
- 通过阈值：对话四维平均分 `>= 0.75`
- 现版回归规则：仅在数据指纹、评测模式、指标版本相同的条件下，相对 baseline 下降超过 5% 才写入 `regressions`；模型/Judge 与失败比例还须人工核对。
- 现版失败口径：直接异常仍留在通过率分母，Judge 无效时不生成占位质量分；对话均分只取有效评分，必须连同有效数/总数展示，全部无效时为 `null`。历史表格沿用其当时版本的记录。

## 2026-09-04 DeepSeek 实测结果

原仓库记载该次运行使用 `deepseek` Profile 和 `deepseek-chat`，执行 12 个意图样本与 5 个对话轮次，5 个对话均记录 `judge_failed=false`。以下作为历史记录保留，本轮没有用新增候选结果替代这些数字。

| 指标 | 结果 |
|---|---:|
| Intent Accuracy | 1.0000（12/12） |
| Intent Macro-F1 | 1.0000 |
| Dialog Overall | 0.9125 |
| Judge fallback | 0/5 |
| 整体通过率 | 1.0000（17/17） |

| 对话轮次 | Relevance | Accuracy | Completeness | Helpfulness | Overall |
|---|---:|---:|---:|---:|---:|
| 退款审核通过后多久能到账 | 0.95 | 0.90 | 0.85 | 0.90 | 0.9000 |
| 登录一直报 401，应该怎么排查 | 1.00 | 1.00 | 1.00 | 1.00 | 1.0000 |
| 你好，我想退款 | 0.90 | 0.90 | 0.80 | 0.85 | 0.8625 |
| 订单号是 #A1024 | 0.95 | 0.90 | 0.85 | 0.90 | 0.9000 |
| 退款多久到账 | 0.95 | 0.90 | 0.85 | 0.90 | 0.9000 |

旧内置集曾将 `logistics`、`technical_crash`、`human_handoff` 分别标成粗粒度的 `query`、`technical`、`escalation`，与当前 Java 版 19 类细粒度枚举不一致。本次已同步修正内置用例，并让仓库评测集直接使用实际枚举标签，防止“代码识别正确、评测标签错误”的假回归。

> 结果边界：当前生成回复和 Judge 使用同一模型供应商，可能存在自评偏差；本报告适合作为可复现工程基线，不等同于独立人工盲测。后续可使用不同模型担任 Judge，并扩大真实业务样本量。

## 复现命令

先启动应用：

```powershell
$env:DEEPSEEK_API_KEY="你的密钥"
$env:SPRING_PROFILES_ACTIVE="deepseek"
.\mvnw.cmd spring-boot:run
```

另开终端运行：

```powershell
.\scripts\run-evaluation.ps1 -Dataset evaluation/legacy-smoke-v1.json
```

或直接调用：

```bash
curl -X POST http://localhost:8080/eval/run \
  -H "Content-Type: application/json" \
  --data-binary @evaluation/eval-dataset.json
```

完整 JSON 默认保存到 `target/evaluation-report.json`，普通运行不会覆盖现有 baseline。只有执行 `./scripts/run-evaluation.ps1 -SaveAsBaseline` 时，才会把本次结果保存到 `data/eval/baseline.json`（二者均不提交 Git）。

## 如何判定是真实 LLM-as-Judge

查看每个 `dialog_*` 结果：`metadata.judge_failed=true` 时，现版 `scores` 为空且不通过，不再将原来的 0.5 fallback 纳入质量均值。`judge_failed=false` 表示评分结构有效，是否由真实模型产生还取决于网关配置；使用 mock、离线 fallback 或无有效密钥的运行不能声称真实模型实测。对外展示时同时给出模型/Profile、运行日期、代码与数据版本、有效评分数、Judge/执行失败数，不应只展示平均分。
