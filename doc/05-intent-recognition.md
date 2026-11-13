# 05. 意图识别（Intent Recognition）

## 1. 阶段目标

第五阶段的目标是让 Jarvis 在正式回答用户问题之前，先理解用户想做什么，再根据识别出的意图选择合适的模型。

在本阶段之前，Jarvis 已经具备：
- 基础 AI 对话能力
- SSE 流式响应
- Prompt Template 管理
- 多轮对话上下文
- 多平台、多模型接入
- ModelRouter 模型路由

本阶段在此基础上增加意图识别能力，使模型选择不再完全依赖默认配置，而是可以根据用户的问题类型进行动态路由。

## 2. 核心概念

意图识别和模型路由是两个不同的职责。

### 2.1 IntentService：识别用户意图

回答的问题是：

**用户想做什么？**

例如：

| 用户输入 | 识别意图 |
|---|---|
| Java HashMap 底层原理是什么？ | GENERAL_QA |
| 帮我分析一下这条 SQL 为什么慢 | SQL_ANALYSIS |
| 接口突然大量出现 502，帮我排查 | TROUBLESHOOTING |
| 查询昨天的订单量是多少 | DATA_QUERY |
| 无法判断用户具体想做什么 | UNKNOWN |

IntentService 负责理解用户输入，并返回结构化的意图识别结果。

### 2.2 ModelRouter：选择模型

回答的问题是：

**应该使用哪个模型处理当前请求？**

ModelRouter 根据意图、模型配置以及用户请求中的模型选择信息，确定最终使用的模型。

例如：

| 意图 | 模型选择示例 |
|---|---|
| GENERAL_QA | 普通问答模型 |
| SQL_ANALYSIS | SQL 分析模型 |
| TROUBLESHOOTING | 故障排查模型 |
| DATA_QUERY | 数据查询模型 |
| UNKNOWN | 默认模型 |

具体映射由模型路由配置决定。

### 2.3 两者之间的关系

IntentService 不负责选择模型，ModelRouter 不负责识别用户意图。

两者职责分离，方便后续独立扩展。

## 3. 意图类型定义

当前定义五种基础意图：

```java
public enum IntentType {

    /**
     * 普通知识问答
     */
    GENERAL_QA,

    /**
     * SQL 分析
     */
    SQL_ANALYSIS,

    /**
     * 故障排查
     */
    TROUBLESHOOTING,

    /**
     * 数据查询
     */
    DATA_QUERY,

    /**
     * 无法判断
     */
    UNKNOWN
}
```

意图识别结果：

```java
public record IntentResult(
        IntentType intent,
        Double confidence,
        String reason
) {}
```

字段说明：

| 字段 | 说明 |
|---|---|
| intent | 识别出的意图类型 |
| confidence | 模型给出的置信度 |
| reason | 意图判断原因 |

注意：confidence 是模型自评的置信度，并不等同于经过校准的真实概率，不能单独作为高风险操作的授权依据。

## 4. IntentService 实现

IntentService 负责调用专门的意图识别模型，将自然语言转换为结构化结果。

基本处理流程：

1. 接收用户输入。
2. 加载意图识别专用 Prompt。
3. 调用配置好的意图识别模型。
4. 获取模型返回内容。
5. 使用 Jackson 将 JSON 解析为 IntentResult。
6. 校验意图类型及置信度。
7. 返回识别结果。

### 4.1 意图识别 Prompt

单独维护意图识别 Prompt，例如：

```text
src/main/resources/prompts/
    system-prompt.md
    intent-classification-prompt.md
```

意图识别 Prompt 的主要要求：

- 明确五种意图的定义。
- 根据用户实际需求进行分类。
- 只返回 JSON。
- 不返回 Markdown 代码块。
- 不输出额外解释文本。
- 无法判断时返回 UNKNOWN。

期望输出格式：

```json
{
  "intent": "SQL_ANALYSIS",
  "confidence": 0.95,
  "reason": "用户希望分析 SQL 执行效率"
}
```

### 4.2 为什么使用非流式调用？

意图识别只需要返回一个简短的结构化结果，不需要像正式对话一样逐字输出。

因此可以使用普通 Chat Completions 请求：

```json
{
  "stream": false
}
```

这样可以简化处理流程，减少 SSE 解析逻辑。

同时，意图识别适合使用响应速度较快的模型，并根据模型及平台能力关闭不必要的思考过程。

注意：max_tokens 主要限制输出 Token，不一定能够限制模型内部思考耗时。

### 4.3 JSON 解析与异常处理

使用 Jackson 将模型返回的 JSON 转换为 IntentResult。

需要考虑：

- JSON 格式错误。
- intent 不属于已定义的枚举。
- confidence 为空或超出 0～1。
- 模型返回空内容。
- 模型调用超时或失败。

异常结果不能直接误判为 GENERAL_QA。

建议将无法解析或无法可靠分类的结果降级为 UNKNOWN，并记录异常日志，方便后续排查。

## 5. 与现有模型体系集成

IntentService 不应该自己创建新的 WebClient。

应复用第四阶段已经实现的模型基础设施：

- LlmProperties：模型及平台配置。
- LlmClientFactory：创建和获取模型客户端。
- ResolvedLlmClient：提供解析后的模型调用信息。
- ModelRouter：根据意图选择正式对话模型。

意图识别模型应有独立配置，避免分类请求默认使用耗时较长的正式对话模型。

同时要避免循环依赖：

意图识别模型不能依赖一个必须先完成意图识别才能工作的 ModelRouter 来选择自身。

## 6. 接入 ChatService

第五阶段完成后，正式对话链路调整为：

```text
ChatRequest
    |
    v
IntentService
    |
    | IntentResult
    v
ModelRouter
    |
    | modelId
    v
LlmClientFactory
    |
    | ResolvedLlmClient
    v
ChatService
    |
    v
SSE 流式响应
```

各组件职责：

| 组件 | 职责 |
|---|---|
| ChatRequest | 接收用户请求 |
| IntentService | 识别用户意图 |
| ModelRouter | 根据意图选择模型 |
| LlmClientFactory | 获取对应模型客户端 |
| ChatService | 执行正式 AI 对话 |
| ConversationService | 管理多轮对话上下文 |

用户明确指定模型时，建议优先尊重用户选择；未指定模型时，再根据识别出的意图进行自动路由。

## 7. 测试与验收

本阶段完成了 IntentService 独立测试，以及接入 ChatService 后的整条链路联调。

重点验收场景：

| 测试场景 | 预期结果 |
|---|---|
| 普通知识问答 | GENERAL_QA |
| SQL 性能分析 | SQL_ANALYSIS |
| 接口异常排查 | TROUBLESHOOTING |
| 业务数据查询 | DATA_QUERY |
| 模糊问题 | UNKNOWN 或合理分类 |
| 不同意图请求 | 能选择对应模型 |
| 用户指定 modelId | 按约定优先级选择模型 |
| 多轮对话 | 意图识别不破坏原有上下文 |
| SSE 正常结束 | 不影响原有流式响应 |
| 异常识别结果 | 不发生错误意图映射 |

**验收结论：第五阶段已完成。**

IntentService 独立接口测试通过，接入 ChatService 和 ModelRouter 后，意图识别与模型路由整条链路联调完成。

## 8. 本阶段的收获

完成第五阶段后，Jarvis 从单纯的模型调用系统，开始具备初步的自主决策能力。

此前：

```text
用户输入
   |
   v
选择默认模型
   |
   v
模型回答
```

现在：

```text
用户输入
   |
   v
识别用户意图
   |
   v
根据意图选择模型
   |
   v
模型回答
```

这也是后续 Agent 能力的重要基础。

## 9. 当前不足与后续方向

目前的意图分类仍然是初步版本。

后续可以逐步扩展：

1. 增加更细粒度的意图分类。
2. 支持复合意图识别。
3. 引入低置信度处理策略。
4. 结合上下文进行意图判断。
5. 根据实际业务场景动态调整路由策略。

暂时不需要提前实现这些能力。

## 10. 下一阶段：Tool Calling

第六阶段将进入工具调用。

目标是让模型不只是告诉用户应该怎么做，而是能够主动请求后端执行具体操作。

例如：

```text
用户：帮我计算一下这批订单的总金额
       |
       v
模型判断需要调用计算器
       |
       v
模型返回 tool_calls
       |
       v
Java 后端执行计算器工具
       |
       v
工具结果返回模型
       |
       v
模型生成最终答案
```

初期先使用简单的 Java 本地工具，跑通完整调用闭环。

之后再逐步扩展到日志查询、数据库只读查询等真实业务能力。

---

**阶段总结：**

第五阶段完成了 Jarvis 的初步意图识别和模型动态路由能力，为后续 Tool Calling、Planner、Agent 执行机制以及最终的 AIOps 故障诊断能力打下基础。