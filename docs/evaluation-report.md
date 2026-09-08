# NexusOps Java 评测报告

## 评测口径

- 数据集：[`evaluation/eval-dataset.json`](../evaluation/eval-dataset.json)
- 意图指标：Accuracy、Macro-F1、每类 Precision / Recall / F1
- 对话指标：LLM-as-Judge 对 relevance、accuracy、completeness、helpfulness 四维评分
- 通过阈值：对话四维平均分 `>= 0.75`
- 回归规则：相对上一次 baseline 下降超过 5% 时写入 `regressions`

## 2026-09-04 DeepSeek 实测结果

本次运行使用 `deepseek` Profile 和 `deepseek-chat`，完整执行 12 个意图样本与 5 个对话轮次。5 个对话结果全部满足 `judge_failed=false`，因此下列对话分数来自真实 LLM-as-Judge，而不是本地 0.5 fallback。

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
.\scripts\run-evaluation.ps1
```

或直接调用：

```bash
curl -X POST http://localhost:8080/eval/run \
  -H "Content-Type: application/json" \
  --data-binary @evaluation/eval-dataset.json
```

完整 JSON 默认保存到 `target/evaluation-report.json`，普通运行不会覆盖现有 baseline。只有执行 `./scripts/run-evaluation.ps1 -SaveAsBaseline` 时，才会把本次结果保存到 `data/eval/baseline.json`（二者均不提交 Git）。

## 如何判定是真实 LLM-as-Judge

查看每个 `dialog_*` 结果：只有 `metadata.judge_failed=false` 时，四维分数才来自模型；若为 `true`，当前实现会返回 0.5 的保守 fallback。对外展示时必须同时给出模型/Profile、运行日期、数据集 commit 和 Judge 失败数，不应只展示平均分。
