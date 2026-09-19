# 评测数据与复现

## 数据集身份

| 文件 | 用途 | 覆盖与限制 |
|---|---|---|
| `eval-dataset.json` | 原有冒烟集，保留以兼容旧命令 | 12 条单标签意图、3 组对话共 5 轮；不是 19 类完整测试 |
| `legacy-smoke-v1.json` | 上述冒烟集的冻结副本 | 内容与原文件一致；历史 12/12 只能归属于该小样本集 |
| `intent-holdout-candidate-v1.json` | 新增候选测试集 | 19 类 × 5 条 = 95 条单标签意图；无对话质量样本 |
| `compound-scenarios-v1.json` | 复合请求人工检查清单 | 4 个场景；检查多问题覆盖、路由与工具轨迹，不混入单标签 Accuracy/Macro-F1 |

95 条是本轮 AI 辅助编写的合成候选样本，标签依据现有 19 类枚举及以下边界制定。它们不是线上真实用户请求，尚未经独立人工盲审，不能宣称“独立人工盲测”或真实业务分布的泛化成绩。候选编写时检查过现有实现，避免了与旧数据及代码示例直接重合；这不保证语义独立，也不意味着不存在设计者偏差。`holdout-candidate` 表示待复核冻结的候选身份，而非已经完成严格独立抽样。

本轮不使用这些候选改动 `IntentRecognizer`、Few-shot、规则或阈值。先人工复核有歧义的标签，记录冻结版本，再评测和存档。之后如果根据其错误样本调整代码或提示词，这一版就转为开发/回归集；对外衡量泛化效果必须另建未见过的新测试集。不要把失败题直接写进代码模板后重新跑出“提升”。

## 标签边界

细粒度意图优先于对应宽泛大类。19 类包括 `other`；不应表述为 19 类都属于业务动作。每类仅 5 条，对措辞变化仍很敏感。

| 标签 | 该候选集采用的边界 |
|---|---|
| `query` | 一般产品、权益、营业时间等咨询，未落入具体领域 |
| `complaint` | 表达服务不满，未明确要求管理升级或人工接入 |
| `request` | 取消、变更预约或套餐等一般执行请求 |
| `greeting` | 寒暄与打招呼 |
| `escalation` | 正式投诉、主管复核、管理层介入 |
| `technical` | 上传、布局、搜索、音频等故障，不是登录/崩溃 |
| `billing` | 费用构成、计费周期、账单说明，不是支付异常/退款/发票 |
| `account` | 账户资料、绑定信息、注销，不涉及安全事件 |
| `feedback` | 正面评价和功能建议 |
| `order_status` | 商家侧订单受理、审核、出库/发货状态 |
| `logistics` | 已发货后的运单、承运、派送、签收 |
| `refund` | 退货退款申请、进度或到账 |
| `invoice` | 开票、补发、抬头与税号 |
| `payment_issue` | 支付失败、重复/错误扣款、支付记录不一致 |
| `account_security` | 盗用、陌生设备、密码重设、验证保护 |
| `technical_login` | 密码未改但无法登录、401、验证码或身份认证故障 |
| `technical_crash` | 客户端退出/崩溃、HTTP 500 等中断性错误 |
| `human_handoff` | 明确要求真人/人工坐席 |
| `other` | 无法理解或明显超出客服业务域的输入 |

`query/billing/technical` 与细分类存在层级关系；`complaint/escalation/human_handoff`、`order_status/logistics` 也有边界。人工复核应先看以上规则，不以现有分类器输出反过来决定“正确答案”。复合请求另做场景检查，因为单标签分数不能证明多 Agent 覆盖。

## 无模型、无密钥的生产降级基线

在项目根目录使用 Java 21，并先构建可执行 JAR：

```powershell
.\mvnw.cmd package
.\scripts\run-evaluation.ps1 -Offline
```

脚本通过 Boot `PropertiesLauncher` 运行 `OfflineIntentBaseline.main`，不启动 Spring 或 HTTP 服务，不读取 `.env`，不连接 Redis/Chroma，不创建模型客户端。它直接实例化生产 `IntentRecognizer`，注入始终抛出异常的 `LlmGateway`，测量真实的“模型失败后：细粒度关键词规则 → 字符 n-gram 模板相似度 → 通用规则”路径，分类器源码保持不变。

默认结果：`target/offline-intent-baseline.json`。模式为 `offline_production_intent_fallback`，包含 95 条预测、数据文件 SHA-256、每类 support/Precision/Recall/F1、Accuracy 与 Macro-F1。这是分类器降级基线，不是 LLM、RAG、Agent 对话或完整 `/chat` 的效果评测。`-Offline -SaveAsBaseline` 会被拒绝，以免覆盖应用流水线 baseline。

当前生产分类器用 `Map` 迭代解决部分同分候选，跨 JVM 运行的同分胜出项可能变化。复现命令固定，但不承诺字节级一致或每次恰好同一分数；保留完整逐题预测，不要择优挑一次结果。该限制没有通过修改 holdout 或分类器来掩盖。

## 应用侧评测

在已正确配置且启动的应用旁运行：

```powershell
# 新的 95 条意图候选；会调用应用配置的模型。
.\scripts\run-evaluation.ps1 -Output target/application-candidate-report.json

# 原 12 条意图与 5 轮对话的冒烟集。
.\scripts\run-evaluation.ps1 -Dataset evaluation/legacy-smoke-v1.json -Output target/application-legacy-report.json
```

脚本不会自动读取、设置或输出密钥。模型模式由应用配置决定，不能仅凭 `evaluation_mode=application_pipeline` 宣称实际模型调用成功。检查 `intent_llm_failure_count`：这是生产识别器报告 LLM 识别失败后走降级的数量；`intent_failure_count` 则是识别调用直接抛异常的数量。模型真实调用还应结合配置、日志/用量和网关行为确认，不能用 mock/fallback 成绩充当真实模型成绩。

对话评测目前直接调用 `AgentOrchestrator + LLMJudge`，携带的是评测器内存中的对话历史。它绕过 `/chat` Controller 的检索、Redis 记忆读写和历史实体补全，不能称完整 `/chat` 端到端评测；这些链路需用 HTTP 场景及集成测试另行验证。

## 指标与失败口径

- 意图 Accuracy 的分母包含全部输入样本。直接异常用内部错误标记计为错，不悄悄跳过；LLM 失败后降级仍产生的预测照常评分，同时披露失败数量。
- Macro-F1 在本次数据集的**真实标签集合**上平均，零分母类按 0 计；95 候选覆盖固定 19 类。异常标记不被当成第 20 类。宏平均先用未舍入的各类分数计算，最终展示四位小数。
- 对话只有四项分数齐全、有限且处于 `[0,1]` 才有效；有效均分 `>=0.75` 通过。Judge 失败或执行异常的 `scores` 为空、`passed=false`，不会把 0.5 或其他占位分当成真实质量。
- `dialog_overall` 只平均有效评分，必须同时展示 `dialog_valid_count/dialog_total`、`judge_failure_count` 和 `dialog_execution_failure_count`；全部无效/没有对话时均分为 `null`。`dialog_invalid_count` 是所有无效对话数；兼容旧字段 `judge_fallback_count` 现在同样表示无效数，不再意味着把 fallback 分纳入均值。
- `pass_rate` 的分母是全部意图样本加全部对话轮次，失败仍留在分母。它混合了两种通过标准，不能替代分别报告 Accuracy 和对话有效覆盖率。没有意图时 Accuracy/Macro-F1 为 `null`。

## 基线可比性与结果保存

只有显式 `-SaveAsBaseline` 才写入应用配置的 baseline 文件。普通评测不覆盖 baseline；保存失败会显式报错。

自动回归比较要求 `dataset_fingerprint`、`metrics_version`、`evaluation_mode` 全部相同；跨 12/95 集、跨 offline/application、旧版指标均返回 `incompatible_dataset_mode_or_metrics` 并跳过比较。匹配时，已有正分指标相对下降超过 5% 才记为回归。

`matched_dataset_mode_metrics` 仅说明这三个条件匹配；模型版本、Judge、提示词、检索语料和运行环境还需人工核对。应用可能进入模型降级，自动模式字段不会为每个样本切换；比较时同时披露并核对失败数量。不同方法在同一个冻结数据集上的效果可作为**显式方法对照**并排展示，但不能当成同一方法的自动回归。

保存完整逐题 JSON、日期、代码 commit/源码版本、数据 SHA-256、模型及 Judge 配置、失败数。历史小样本和新增候选的成绩分开列出；不要宣称“95 条人工盲测 100%”或从一次结果推导线上准确率。
