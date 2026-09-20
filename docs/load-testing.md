# 可复现的 Agent 并发与故障验证

本压测调用生产 `AgentOrchestrator`、`AgentExecutionConfig`、`TechnicalAgent`、`BillingAgent` 和 `BaseAgent.handle`。仅将 `LlmGateway` 替换成可控延迟或异常的实现，以复现队列饱和、超时、局部失败和故障后恢复。它用于验证编排行为，未经过 HTTP、意图识别、RAG、Redis、回答验证或真实模型服务，不能据此声称系统的线上 QPS、真实模型吞吐或整条 `/chat` 链路的 P95。

## 运行

需要 JDK 21 和 PowerShell。首次运行还需要 Maven 依赖下载条件；无需 API Key、Redis 或 Spring 应用进程。在项目根目录执行：

```powershell
./scripts/run-load-test.ps1
```

默认输出 `target/load-test-result.json`，包含运行环境、参数、关键源码 SHA-256、15 项行为检查、每阶段统计和全部请求明细。脚本在任何行为检查未通过时返回失败；不要只查看延迟或吞吐列。

可指定输出文件，保留独立复现记录：

```powershell
./scripts/run-load-test.ps1 -OutputPath target/load-run-1.json
./scripts/run-load-test.ps1 -OutputPath target/load-run-2.json
```

可以通过 `-MavenExecutable` 和 `-MavenRepository` 指定已有 Maven 与本地仓库；依赖完整时可加 `-Offline`。`-AllowExperimentalByteBuddy` 仅供在较新本地 JDK 上兼容既有测试依赖，CI 使用 JDK 21，不需要该参数。

`AgentLoadBenchmark` 是显式运行的 JUnit 入口，名称不匹配 Surefire 默认测试命名规则。因此普通 `verify.ps1` 不运行这轮负载，也不会将它列为跳过测试；指标计算测试 `AgentLoadBenchmarkMetricsTest` 仍参加普通验证。CI 在普通验证后单独调用压测脚本，并上传 JSON 证据。

## 默认负载和预期行为

本压测所有阶段始终共用同一个真实 `ThreadPoolExecutor`：4 个 Agent 工作线程、8 个排队任务槽、每请求 Agent 执行阶段统一 1000 ms 时限。可控模型正常响应等待 30 ms；这不是生产默认配置。8 次双域预热不计入统计，以下 172 个请求计入统计。

| 阶段 | 请求数 | 客户端并发 | 每请求域数 | 验证内容 |
| --- | ---: | ---: | ---: | --- |
| `steady_single` | 40 | 4 | 1 | 单域正常请求全部成功 |
| `steady_multi` | 40 | 2 | 2 | Technical 和 Billing 同时执行，均成功 |
| `burst_saturation` | 20 | 20 | 1 | 模型调用暂由闸门阻塞，队列填满并产生拒绝后释放闸门；默认应有 8 个溢出请求被拒绝 |
| `slow_timeout` | 8 | 4 | 1 | 模型等待 2000 ms，8 个请求均在统一时限下报告超时 |
| `partial_failure` | 12 | 2 | 2 | Technical 抛出异常，Billing 成功答案保留；整体记录为部分成功 |
| `all_domains_fail` | 12 | 2 | 2 | 两个模型调用均抛出异常，整体不得计为成功 |
| `recovery_same_executor` | 40 | 4 | 1 | 恢复正常模型行为，复用前述线程池，40 个请求全部成功 |

负载为有限请求的闭环客户端：每个客户端收到结果后才发下一请求，各阶段首波同步开始。队列容量按 Agent 任务计，双域请求会提交两个任务。每阶段前后检查 Agent 活动线程为 0、队列为空，再进入下一阶段；不在阶段之间重建线程池。每阶段会创建新的编排器及 Agent 实例，因此“同池恢复”证明线程池可继续服务，不代表所有业务组件或长期连接完成恢复。

故障模型配合线程中断，因此超时后可排空线程池。真实 HTTP 客户端是否停止远端调用、是否继续占用连接，需要另行验证。线程池大小、队列上限、拒绝、超时、部分失败和恢复均有断言；不会通过扩大队列或重新建池掩盖失败。

## 指标定义

- **请求结果**：`success`、`partial_success`、`all_failed` 互斥。依据预期域、模型替身的固定成功标记和 `agent_execution:*` 失败 Trace 判定；返回一段错误文本不算成功。
- **失败原因**：`rejected`、`timeout`、`failed`、`interrupted` 按请求是否出现该原因分别计数；一个多域请求可能同时拒绝一个域并超时另一个域，所以原因计数和比率可以重叠。`request_exception` 单独记录逃逸出编排器的异常。原因比率的分母均为该阶段全部实测请求数，不仅是失败请求数。
- **域统计**：`successful_domain_count` 是成功域总数，`total_failed_domain_count` 是所有原因的失败域总数。`failed_domain_count` 只表示 Trace 原因为 `failed` 的域数；另有 `timeout_domain_count` 等各原因域数。无返回 Trace 的驱动异常不虚构 Trace。
- **请求延迟**：使用单调时钟，覆盖 `orchestrator.run` 调用，包括 Agent 排队、提示词处理、执行和编排结果汇总；不含调用前客户端执行器的调度等待。分别提供全部请求和完全成功请求的延迟，后者不包含拒绝、超时或部分成功。
- **P50/P95/P99**：使用最近秩法，将 n 个值升序排列，取第 `ceil(p × n)` 个值。成功样本为空时分位数为 `null`，不记为 0。小样本的 P95/P99 常等于最大值，不能视作稳定尾延迟估计。
- **吞吐**：完全成功请求数除以该阶段实测秒数，与“所有完成请求数除以同一秒数”分别输出。快速拒绝、错误返回不会提高成功吞吐；部分成功也不计入完全成功吞吐。
- **队列与活跃线程**：每 1 ms 及请求边界观察一次，记录观察到的最大值。采样最大值可能低于瞬时峰值；有界队列容量和线程数限制由实际线程池结构保证。预热、创建客户端和阶段间排空不在阶段计时内。

这套闭环、短时、172 请求的验证主要复现机制正确性；不覆盖持续高压、开放到达率、协调遗漏校正、GC 稳态、多进程竞争或系统容量拐点。跨机器、JDK、系统负载的毫秒值会变化，应比较配置、失败分类、恢复结果和原始样本，不应要求吞吐逐位一致。

## 调参范围

脚本参数均写入结果 `configuration`。只有默认组合被作为固定回归场景；更改参数后必须重新检查全部行为检查和样本。

| 参数 | 默认值 | 允许范围 |
| --- | ---: | --- |
| `AgentThreads` | 4 | 2–16 |
| `QueueCapacity` | 8 | 1–64 |
| `TimeoutMs` | 1000 | 200–3000 ms |
| `ModelDelayMs` | 30 | 1–100 ms |
| `SteadyRequests` | 40 | 至少等于 AgentThreads，最多 100 |
| `FaultRequests` | 12 | 1–50 |
| `BurstOverflow` | 8 | 1–32 |

例如降低超时并增加队列后，饱和阶段已接收的请求可能在等待中超时；这会保留在原始记录中。若在半个执行时限内未观察到预期数量的拒绝，驱动仍会释放闸门以便清理任务，但行为检查会失败，不会将该次运行当作有效的饱和复现结果。

可用于面试的准确表述是：“用真实有界执行器和生产编排代码，在固定模型替身下复现单域/多域正常负载、队列拒绝、统一超时、部分失败保留和同池恢复，分别记录成功吞吐、请求 P95 和失败原因比率。”对外引用具体数字时应附带相应 JSON、参数、环境及源码指纹。
