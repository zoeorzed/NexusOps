# NexusOps

[![Java CI](https://github.com/zoeorzed/NexusOps/actions/workflows/ci.yml/badge.svg)](https://github.com/zoeorzed/NexusOps/actions/workflows/ci.yml)

![NexusOps Enterprise Multi-Agent Operations Platform](docs/assets/nexusops-social-preview.jpg)

> 支持知识增强、记忆、结构化多 Agent 路由与评测闭环的企业智能运营协同中枢

NexusOps 是 EchoMind Java 工程面向企业运营协同场景的产品化表达。它将订单履约、技术故障、账务异常、政策咨询和人工升级等请求统一接入一条可治理的 Agent 处理链路，而不只是完成单轮问答。

```text
NexusOps = Intent Recognition + Hybrid RAG + Memory
         + Multi-Agent Routing + Dynamic Skills
         + Tool Reliability + Monitor + Evaluation
```

> 仓库说明：项目最初以 `EchoMind` 为工程代号开发，因此 Java 包名、配置前缀和部分基础设施标识仍保留 `echomind`。NexusOps 是对外产品名称；保留内部标识可以避免无业务价值的破坏性重命名。

## 解决什么问题

企业运营请求经常跨越客服、技术和账务边界。传统流程依赖人工判断、查阅知识、跨部门转发和事后复盘，容易造成响应慢、上下文丢失、复合问题漏答以及规则更新不同步。

NexusOps 将这条流程工程化：

```text
业务请求
  -> 读取会话记忆
  -> 识别细粒度意图、意图组、实体和紧急程度
  -> 按意图决定是否触发知识检索
  -> 查询改写、多路召回与重排
  -> 生成 primary_agent + supporting_agents 路由决策
  -> 注入知识、上下文和动态 Skills
  -> 专业 Agent 协同回复
  -> 回答校验与人工升级判断
  -> 写入记忆、记录工具轨迹
  -> Monitor 观测与 LLM-as-Judge 评测
```

## Agent 角色

| 代码实现 | NexusOps 对外角色 | 主要职责 |
|---|---|---|
| `GeneralAgent` | 运营协调 Agent | 通用咨询、订单物流、会员权益、信息澄清与跨域协调 |
| `TechnicalAgent` | 技术可靠性 Agent | 登录失败、错误码、页面异常、系统故障与排障建议 |
| `BillingAgent` | 收入与合规 Agent | 退款、发票、支付异常、订阅和账务边界 |
| `ESCALATION` | 运营升级通道 | 标记高风险、低置信度或明确要求人工处理的请求 |

复合问题不会被强制压缩成单一领域。例如“登录一直 401，而且刚才还重复扣款了”可以由技术可靠性 Agent 主处理，同时让收入与合规 Agent 提供辅助意见，并返回路由原因。

## 核心能力

### 可解释的多 Agent 编排

- 识别细粒度 `intent` 和归一化 `intent_group`
- 提取订单号、金额、日期和错误码等结构化实体
- 生成 `primary_agent`、`supporting_agents` 和 `routing_reason`
- 综合意图、实体、紧急程度与历史运行表现进行路由
- 对复合问题并行调用主、辅 Agent，并保留人工升级通道

Agent 执行使用独立的有界线程池（默认 8 个线程、32 个排队任务）。单领域和多领域请求共用执行阶段的 30 秒截止时间，排队时间计入预算；超时、拒绝或某个 Agent 失败时，保留已完成领域的回答，并在响应和 Trace 中标明未完成的部分。该预算不包含前置意图识别、检索和后置校验，也不保证中断底层 HTTP 调用。

### 按意图触发的 Hybrid RAG

```text
文档导入
  -> LangChain4j recursive splitter
  -> JSON 持久化
  -> 本地文档索引

查询
  -> LLM 查询改写
  -> 多子查询并行召回
  -> BM25 + 本地 hash vector
  -> 加权融合
  -> LLM rerank
  -> 失败时回退到融合分排序
```

当前 Java 版的主检索源是本地 Hybrid RAG，知识库、长期记忆和用户画像使用 JSON 持久化。仓库保留 ChromaDB 容器和 Spring AI Chroma 依赖作为后续 VectorStore 接入基础，但不将其描述为当前主召回实现。

### 记忆增强

- Redis 保存当前会话工作记忆
- 本地持久化保存历史会话摘要和用户画像
- 通过相关历史召回增强多轮对话连续性
- 异步更新用户画像，避免阻塞主响应链路
- 摘要生成后通过 Redis Lua 比较原消息快照，再原子保存摘要并截断旧消息；期间有新写入则放弃本次压缩，摘要失败保留原文
- 在回答生成前补全历史实体，区分当前轮实体与结合历史解析后的实体

### 动态 Skills

运营规则、技术排障 SOP 和账务处理边界存放在 `skills/` 中，由 `SkillManager` 动态加载并按 Agent 注入。业务规范可以独立维护，无需写死在 Agent 类中。

### 工具可靠性与请求追踪

`KnowledgeToolManager` 为知识检索提供 TTL 缓存、超时、熔断、fallback、rerank 和调用统计。每次请求会生成 `request_id`，并记录 Agent 路由、工具使用、成功状态、降级状态和延迟。

> 当前实现是面向知识检索的专用工具治理层，并非完整的通用 MCP 注册平台。

### 监控与评测闭环

- Agent 成功率、延迟和路由惩罚反馈
- Intent Accuracy、Macro-F1 和分类指标
- LLM-as-Judge 多维回复质量评分
- baseline 保存与回归检测
- Prometheus、Actuator 和 Webhook 告警

## 技术栈

| 层次 | 实现 |
|---|---|
| 语言与框架 | Java 21、Spring Boot 3.5 |
| AI | Spring AI 1.1、LangChain4j |
| 模型 | Anthropic、DeepSeek，可通过 Spring Profile 切换 |
| 工作记忆 | Spring Data Redis |
| 知识与长期记忆 | JSON 持久化、BM25、本地 hash vector、LLM rerank |
| 规则 | Markdown Skills 动态加载 |
| 可观测性 | Actuator、Micrometer、Prometheus、请求工具轨迹 |
| 评测 | Intent 指标、Macro-F1、LLM-as-Judge、回归检测 |
| 部署 | Docker Compose、Nginx、Redis、Prometheus、ChromaDB 预留 |

完整的可渲染架构图、分层职责和实现边界见 [架构说明](docs/architecture.md)。真实 Java API、RAG 和 Agent Trace 输出见 [运行示例](docs/api-examples.md)。

## 项目结构

```text
src/main/java/com/echomind/
├── api/          # HTTP 接口和 DTO
├── agent/        # Agent 编排、专业 Agent、回答校验
├── evaluation/   # 端到端评测和 LLM Judge
├── intent/       # 意图识别、实体提取与结果模型
├── knowledge/    # 知识库、文档切分与检索
├── llm/          # Spring AI 模型网关
├── memory/       # Redis 工作记忆、历史摘要和用户画像
├── monitor/      # 性能监控、路由反馈与告警
├── skill/        # 动态 Skills 加载
├── tool/         # 知识工具治理、缓存、熔断与统计
└── trace/        # 请求和工具调用轨迹

skills/           # 运营、技术和账务处理规范
config/           # Nginx 与 Prometheus 配置
docs/             # 产品与技术架构文档
```

## API

默认应用端口为 `8080`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/chat` | 统一业务请求入口 |
| POST | `/search` | Hybrid RAG 检索 |
| POST | `/knowledge/add` | 批量添加知识文档 |
| POST | `/knowledge/upload` | 上传 `.txt`、`.md` 或 `.json` 文档 |
| GET | `/knowledge/stats` | 知识库统计 |
| GET | `/trace/tool/{requestId}` | 查询单次请求的工具轨迹 |
| GET | `/trace/tools` | 查询近期工具轨迹 |
| GET | `/monitor` | 运行监控摘要 |
| GET | `/metrics` | Prometheus 指标 |
| POST | `/eval/run` | 运行端到端评测 |
| GET | `/health` | 健康检查 |
| GET | `/docs` | Swagger UI |

对话请求示例：

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "登录一直 401，而且刚才还重复扣款了",
    "user_id": "u1001"
  }'
```

响应会包含请求 ID、意图、意图组、结构化实体、主辅 Agent、路由原因、知识使用和校验状态等信息。

## 可复现评测

仓库提供与 Java 版细粒度意图枚举一致的 [95 条候选评测集](evaluation/intent-holdout-candidate-v1.json)、[运行脚本](scripts/run-evaluation.ps1) 和 [评测报告](docs/evaluation-report.md)。

```powershell
.\scripts\run-evaluation.ps1
```

普通评测只与现有 baseline 对比，不会覆盖它。确认本次结果适合作为后续回归基准时，再显式执行 `./scripts/run-evaluation.ps1 -SaveAsBaseline`。

扩充评测集与离线对照说明见 [评测说明](evaluation/README.md)。旧的 12 条意图样本仍作为历史烟测集保留；新增 95 条合成候选样本覆盖 19 类意图，不能据此声称已完成独立人工盲测。

2026-09-19 完整验证：58 项 Java 测试与 6 项 Python 测试通过；在上述 95 条冻结候选上，DeepSeek 意图识别为 93/95（Accuracy 97.89%，Macro-F1 0.9787，识别调用失败及降级均为 0），离线生产降级路径为 50/95。三条真实 HTTP 演示另行验证检索、复合路由和跨轮记忆。环境、逐题结果、错例及范围限制见 [本轮验证记录](docs/verification-2026-09-19.md)。

没有真实模型密钥时，只能报告离线降级路径的结果。对话 Judge 失败会明确标记，失败项不作为有效质量评分，仍计入测试总数与失败数；不同数据集或评分口径不会直接做 baseline 回归比较。

## 一次完成测试与打包

准备一个仅用于测试的本机无密码 Redis，使用 JDK 21，在项目根目录执行：

```powershell
./scripts/verify.ps1 -RedisPort 6379
```

该脚本执行干净构建、完整测试与可执行 JAR 打包，并要求 Redis 集成测试实际运行且没有失败或跳过。测试摘要、各套件结果和 JAR SHA-256 保存在 `target/verification-summary.json`；原始 JUnit 报告在 `target/surefire-reports/`。测试使用确定性模型替身，不调用真实模型服务。GitHub Actions 使用 Redis 7.4.2 和同一验证脚本，并上传验证报告。

真实模型演示与历史证据见 [演示说明](demo/README.md)。执行演示会使用配置的模型 API；历史样例、模拟测试结果与新运行结果应分别记录。

并发配置可以通过 `AGENT_EXECUTION_THREADS`、`AGENT_EXECUTION_QUEUE_CAPACITY`、`AGENT_EXECUTION_TIMEOUT_MS` 调整。线程耗尽时快速返回繁忙提示，已超时的远程请求仍可能由 HTTP 客户端继续等待，因此需要同时考虑模型服务自身的连接、读取超时与限额。

## 快速启动

### 环境要求

- JDK 21+
- Docker Desktop 或 Docker Engine
- Anthropic 或 DeepSeek API Key

### Docker Compose

```bash
cp .env.example .env
docker compose up -d --build
```

Windows PowerShell：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

编辑 `.env`，填写模型 API Key。生产环境必须替换示例 Redis 密码。

| 服务 | 地址 |
|---|---|
| NexusOps Java App | `http://localhost:8080` |
| Nginx | `http://localhost:8081` |
| Swagger UI | `http://localhost:8080/docs` |
| Prometheus | `http://localhost:9091` |
| ChromaDB（预留） | `http://localhost:8002` |
| Redis | `localhost:6380` |

常用验证：

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
curl http://localhost:8080/metrics
```

## 本地数据与安全

- `.env`、`data/`、`target/` 和 IDE 配置均被 Git 忽略
- `data/java/knowledge-store.json` 保存知识库数据
- `data/java/memory-store.json` 保存长期记忆和用户画像
- `data/eval/baseline.json` 保存评测基线
- 不要将真实 API Key 或生产 Redis 密码提交到仓库

## 当前实现边界

NexusOps 当前是可运行的 Java 工程实现，同时保留清晰的演进边界：

- ChromaDB 已提供依赖与部署容器，但当前主检索仍是本地 Hybrid RAG
- 工具治理目前聚焦 `knowledge_search`，尚未扩展为通用 MCP Tool Registry
- 请求轨迹保存在进程内的有界队列中，重启后不会保留
- 高风险请求提供升级标记，真实工单或人工队列需要对接企业系统
- 已覆盖意图识别、主辅路由、请求轨迹、熔断、Agent 超时与部分失败，以及真实 Redis 记忆压缩；控制器组件测试使用替身，不等同于完整 HTTP 或真实模型评测
- 客户端传入的 `user_id` 和 `conv_id` 用于演示会话分离，当前没有服务端登录鉴权

这些边界不会影响当前演示链路，也避免将规划能力描述成已经完成的实现。

## 名称说明

```text
产品名称：NexusOps 企业智能运营协同中枢
工程代号：EchoMind
代码包名：com.echomind
```

保留 EchoMind 工程标识，既能维持 Git 历史和部署兼容性，也能让 NexusOps 作为产品层名称独立演进。
