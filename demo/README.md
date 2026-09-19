# NexusOps 面试演示（Java 版）

知识数据见 `knowledge.json`，全部为虚构演示政策。使用独立数据目录启动 Java 服务，设置已有的 DeepSeek 配置、Redis 地址，以及 `LLM_FALLBACK_ENABLED=false`，避免把本地兜底回复当作真实模型效果。不要把真实用户资料导入演示环境。

每次验证新政策时，`ECHOMIND_DATA_DIR` 应指向新的空目录。导入接口不会删除已有政策；在旧知识库追加新文档无法消除历史的“24小时核验”文档。保留旧目录和历史证据，不需要删除原数据。

在项目根目录运行：

```powershell
python demo/run_demo.py --base-url http://127.0.0.1:8080 --output demo/results/new-run.json
```

脚本导入两篇演示知识，发出以下三条 `/chat` 请求并保存真实响应和 Trace 到指定文件。每次请使用新的输出文件名；省略 `--output` 时会覆盖 `demo/latest-result.json`。每次生成新用户和会话 ID，保留第一、二轮的同一会话。运行会使用配置的模型服务并产生 API 用量；模型调用异常会保存失败类型，不会伪造通过。

1. 复合问题：“我登录失败并提示401，订单#A20260914001查不到，而且银行卡重复扣款299元”。检查 Technical 与 Billing 均参与，回答分领域；主 Agent 可能受意图判定影响，以实际 Trace 为准。
2. 记忆第一轮：“我的订单号是#A20260914002，重复扣款299元，请记住这笔订单。”
3. 同一会话第二轮：“那这笔订单怎么申请退款？”检查 `current_entities` 与 `resolved_entities` 的区别，后者应保留订单号；核对自然语言回答是否承接上下文。

人工检查：演示补充知识不再另设核验或到账时限，统一引用默认《退款政策》中的阶段：审核 1–3 个工作日，审核通过后到账 5–7 个工作日。核验、审核与到账不得混淆；不得声称已执行退款、建工单或查询真实支付流水。这些后台工具并未实现。`verified=true` 是模型评审结果，不是业务完成证明。

已补控制器级测试，验证历史实体在调用 Agent **之前**注入；Redis 实测覆盖压缩后顺序、并发写入、摘要异常和近期会话隔离。它们不等同于真实模型回答质量评测。当前 NexusOps 的 `user_id` 是客户端传入的演示标识，没有登录认证，不能声称实现了生产级用户权限隔离。

2026-09-14 的历史真实 DeepSeek 演示中，复合路由、知识检索和两轮记忆检查通过，实际响应与 Trace 见 `latest-result.json`，质量观察见 `../docs/nexus-live-verification.md`。该结果来自旧提示词和旧演示政策；新代码结果应另存文件并记录版本，不能把历史结果当作新提示词效果。`docs/api-examples.md` 同样是历史样例。

2026-09-19 新运行的 [完整响应和 Trace](results/2026-09-19.json) 通过 10 项结构检查；[验证记录](../docs/verification-2026-09-19.md) 单独记录内容复核及仍存在的冗长问题。原始 JSON 中 `pending_manual_review` 是采集结束时的状态；随后由 AI 助手逐条复核并在验证记录中说明，不将其改写成独立人工评分。

Redis 集成测试：启动一个无密码的独立 Redis 后运行 `./mvnw -Dredis.test.port=6379 test`。不提供端口时 4 项 Redis 测试会跳过；CI 已加入 Redis 服务和参数。Java 21 是项目基线；本次本机用 Java 25 的 release 21 编译，Mockito 额外启用 `-Dnet.bytebuddy.experimental=true`。
