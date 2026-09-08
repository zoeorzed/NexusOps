# Java API 实际响应示例

以下内容于 2026-09-04 从 Java 版本地服务实际采集，运行环境为 DeepSeek `deepseek-chat` + Docker Redis。模型鉴权、知识检索、Agent 路由、回答校验、记忆和 Trace 均经过真实链路；动态字段如 `request_id`、时间戳和耗时每次运行都会变化。

## 1. 多 Agent 路由响应

请求：

```http
POST /chat
Content-Type: application/json

{
  "message": "登录提示 401；同时扣款、账单和支付都异常，需要退款并开发票",
  "user_id": "github-demo-user",
  "conv_id": "github-demo-final"
}
```

实际响应节选：

```json
{
  "conv_id": "github-demo-final",
  "request_id": "ebdfc0af",
  "response": "好的，我理解您遇到了登录和支付相关的多重问题。我们一步步来处理，请别着急。首先，关于您提到的 401 错误……",
  "intent": "refund",
  "intent_group": "billing",
  "agent_type": "billing",
  "primary_agent": "billing",
  "supporting_agents": [],
  "routing_reason": "intent=refund, group=billing, primary=billing, supporting=none, scores=[billing=1.20, technical=0.38, general=0.10]",
  "routing_confidence": 1.0,
  "escalated": true,
  "latency_ms": 4964,
  "knowledge_used": true,
  "verified": true,
  "grounded": true,
  "entities": {"error_code": ["401"]},
  "current_entities": {"error_code": ["401"]},
  "resolved_entities": {"error_code": ["401"]},
  "intent_confidence": 0.595,
  "intent_source_scores": {"llm": 0.85, "embedding": 0.0891, "pattern": 0.5, "refined_by_pattern": 0.5}
}
```

`response` 为真实回复的节选，路由字段和数值保持原始响应。这个样例也展示了当前路由边界：请求包含技术信号，但 Billing Agent 得分最高，Technical Agent 未达到 supporting Agent 阈值；它不是人为拼接的多 Agent 演示。

## 2. Hybrid RAG 检索结果

请求：

```bash
curl -X POST "http://localhost:8080/search?query=退款多久能到账&topK=3"
```

实际返回的第一条结果：

```json
{
  "query": "退款多久能到账",
  "reranked": true,
  "results": [
    {
      "title": "退款政策",
      "content": "用户在购买后 7 天内可以申请无理由退款。退款申请提交后，系统会在 1-3 个工作日内审核。审核通过后，款项将在 5-7 个工作日内退回原支付账户。商品已发货时，需要先完成退货流程。",
      "score": 1.0,
      "chunk": 0,
      "metadata": {"source": "echomind-java", "title": "退款政策", "chunk_index": 0}
    }
  ]
}
```

分数由当前 Java 主检索链路的 BM25 与本地 hash vector 融合产生；`reranked=true` 表示经过重排阶段。当真实模型不可用时，管理层会保留融合排序并以 fallback 继续返回。

## 3. Agent 路由与工具 Trace

![Agent routing trace](assets/agent-trace-example.png)

查询方式：

```bash
curl "http://localhost:8080/trace/tools?limit=1"
curl "http://localhost:8080/trace/tool/ebdfc0af"
```

完整字段包括主辅 Agent、路由结果、知识使用、工具成功与降级状态、缓存、重排和分段耗时。PNG 由 `scripts/render_trace.py` 直接读取真实 Trace 接口生成，可以通过替换 `request-id` 重现：

```powershell
python -m pip install -r scripts/requirements.txt
python scripts/render_trace.py --request-id ebdfc0af
```
