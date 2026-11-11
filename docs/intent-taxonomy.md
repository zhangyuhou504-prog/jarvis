# Jarvis 意图分类体系与回归基准

> 本文件是第五阶段（意图识别）的统一基准，供 `IntentService` / `IntentController`
> 开发、调优与回归测试使用。改动 `prompts/intent-classify.md` 后，必须用文末
> 「回归测试集」跑一遍确认不引入误判。

- 分类 Prompt：`src/main/resources/prompts/intent-classify.md`
- 枚举定义：`src/main/java/com/example/jarvis/model/IntentType.java`
- 结果结构：`src/main/java/com/example/jarvis/model/IntentResult.java`
- 识别服务：`src/main/java/com/example/jarvis/service/IntentService.java`
- 独立测试接口：`POST /jarvis/intent`（`IntentController`）

---

## 一、第一版意图分类（5 种）

| 意图编码 | 含义 | 处理方式 | 用户输入示例 |
|---|---|---|---|
| `GENERAL_QA` | 普通知识问答 | 直接组织 Prompt 调用模型回答 | Java HashMap 怎么扩容？ |
| `SQL_ANALYSIS` | SQL 分析 | 使用 SQL 分析模板（纯分析，不执行） | 帮我分析这条 SQL 为什么慢 |
| `TROUBLESHOOTING` | 故障排查 | 后续接入日志/监控/历史故障查询 | Redis 为什么频繁连接超时？ |
| `DATA_QUERY` | 数据查询 | 后续接入只读数据库查询工具 | 帮我查一下订单 123 的状态 |
| `UNKNOWN` | 无法明确判断 | 追问用户，不猜测 | 帮我处理一下 |

> 当前只是定义任务类型，工具（日志、数据库）接入前，`TROUBLESHOOTING`/`DATA_QUERY`
> 暂按"能力未接入"处理，不悄悄降级成 `GENERAL_QA`（避免模型编造真实数据）。

## 二、识别结果结构

```json
{
  "intent": "TROUBLESHOOTING",
  "confidence": 0.95,
  "reason": "用户希望排查 Redis 连接超时"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `intent` | `IntentType` 枚举 | 只能是 5 种之一，全大写加下划线 |
| `confidence` | `Double` | 模型自我评估，**非校准概率**；v1 仅用于日志/调试，**不**作为路由开关 |
| `reason` | `String` | 判断理由，便于调试与后续评估 |

`confidence` 在 `IntentService.sanitize()` 中归一化：null → 0.0，越界截断到 [0,1]。

## 三、判定规则（已固化进 Prompt）

两条判定轴：
1. **是否需要系统"执行一个工具"？** 需要（查库/查日志）→ `DATA_QUERY`；不需要（纯推理）→ 其余。
2. **对象是"知识"还是"正在发生的问题"还是"真实数据"？**

### TROUBLESHOOTING vs GENERAL_QA（最容易混淆，重点）

- **TROUBLESHOOTING**：描述**一个具体的故障/异常现象**——具体错误、性能劣化、异常行为
  （超时、报错、OOM、变慢、P99 飙升、连接失败、崩溃等），用户希望定位"为什么/怎么排查"。
  **即使没有"我的系统"这类措辞，只要描述的是具体故障现象，就是 TROUBLESHOOTING。**
- **GENERAL_QA**：问**通用概念/原理/知识**，不针对某个正在发生的具体故障。

### 其他边界

- 让模型"写/生成"一段 SQL → `GENERAL_QA`（既不是 `DATA_QUERY` 也不是 `SQL_ANALYSIS`）。
- 无法明确判断 → `UNKNOWN`（由模型**主动**判定，不靠 confidence 阈值触发）。

## 四、健壮性约定

`IntentService.recognize()` **永不返回 null、永不向调用方抛异常**。以下任何一步失败
都回落 `UNKNOWN`（confidence=0.0，reason 记录失败原因）：

- 模型调用网络/超时失败
- 响应缺少 `choices` 或 `content` 为空
- 模型输出不是合法 JSON / 未包含 JSON 对象
- 反序列化失败（意图值不在枚举内、字段类型不符）

容错提取：模型偶尔会包 markdown 代码块或加解释文字，`extractJsonObject()` 直接截取
第一个 `{` 到最后一个 `}` 之间的内容。

## 五、回归测试集（12 条）

改动分类 Prompt 后，跑 `POST /jarvis/intent` 逐条验证。
（2026-11-11 实测：`gjld-qwen` 12/12；`mxbc-qwen` 11/12，仅第 5 条为边界差异。）

| # | 输入 | 期望意图 |
|---|---|---|
| 1 | Java HashMap 怎么扩容？ | GENERAL_QA |
| 2 | 什么是 JVM 逃逸分析？ | GENERAL_QA |
| 3 | Redis 连接超时是什么原理？ | GENERAL_QA |
| 4 | 帮我分析这条 SQL 为什么慢：SELECT * FROM orders | SQL_ANALYSIS |
| 5 | 索引 (a,b) 能命中 WHERE b=1 AND a=2 吗？ | SQL_ANALYSIS |
| 6 | Redis 为什么频繁连接超时？ | TROUBLESHOOTING |
| 7 | 服务启动后偶尔 OOM，怎么排查？ | TROUBLESHOOTING |
| 8 | 接口 P99 从 50ms 涨到 2s，帮我定位 | TROUBLESHOOTING |
| 9 | 帮我查一下订单 123 的状态 | DATA_QUERY |
| 10 | 最近 7 天的日活是多少？ | DATA_QUERY |
| 11 | 帮我处理一下 | UNKNOWN |
| 12 | 继续 | UNKNOWN |

> **边界用例 #5 说明**：「索引能命中吗」措辞是 yes/no 问法，介于 SQL_ANALYSIS（索引/查询
> 分析）与 GENERAL_QA（通用知识）之间。实测 `gjld-qwen` 判 SQL_ANALYSIS、`mxbc-qwen` 判
> GENERAL_QA，属模型间在模糊输入上的正常差异，非 prompt 缺陷。若需收敛，可加通用规则
> 「涉及索引/执行计划/查询优化的分析 → SQL_ANALYSIS」，但不要为单个字符串硬编码（防 overfit）。

快速回归脚本（PowerShell）：

```powershell
$body = '{"input":"Redis 为什么频繁连接超时？"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8081/jarvis/intent `
  -ContentType "application/json" -Body $body
```

## 六、已知限制与后续

- `confidence` 未做校准，不能当准确率用；5.4 接 `ModelRouter` 时**不用**它做路由阈值。
- 意图识别用独立模型（`jarvis.intent.model-id`），与主对话模型解耦——分类输出短，
  应指向更快/更稳的模型，避免用慢模型导致非流式调用超时。
- 尚未接入 `ChatService`/`ModelRouter`（当前仅独立测试）；5.4 将 `IntentResult`
  参与模型选择 / 追问 / "能力未接入"提示。
- 未做自动意图识别升级（Planner/Agent），当前是确定性单次分类。
