# 第四阶段：模型接入与管理

## 1. 阶段目标

本阶段主要解决 Jarvis 从单一模型调用向多模型、多平台扩展的问题。

完成后，Jarvis 应具备：

- 支持多个模型平台。
- 支持多个模型配置。
- 根据模型 ID 获取对应模型客户端。
- 支持请求指定模型。
- 支持默认模型。
- 根据请求条件进行模型路由。
- 兼容不同模型平台的 SSE 流式响应差异。

## 2. 整体架构

```text
ChatRequest
    |
    v
ModelRouter
    |
    | 选择 modelId
    v
LlmClientFactory
    |
    | 解析模型配置
    v
LlmProperties
    |
    +---- Model 配置
    |
    +---- Connection 配置
              |
              v
         WebClient
              |
              v
       模型平台接口
```

各组件职责：

| 组件 | 职责 |
|---|---|
| ChatRequest | 接收用户请求及可选模型 ID |
| ModelRouter | 根据请求选择模型 |
| LlmClientFactory | 根据模型 ID 获取对应客户端 |
| LlmProperties | 管理模型及平台连接配置 |
| WebClient | 负责 HTTP 请求和 SSE 流式通信 |
| ChatService | 编排对话流程、会话上下文及流式响应 |

## 3. 多模型配置设计

采用两层配置结构。

### 3.1 Connection：平台连接配置

负责描述模型平台的连接信息：

- baseUrl
- apiKey

### 3.2 Model：模型配置

负责描述具体模型：

- connection：所属平台连接
- modelName：平台实际模型名称
- temperature：采样温度
- maxTokens：最大输出 Token 数
- enableThinking：是否启用思考模式（以项目实际配置字段为准）

这种设计将平台连接与模型本身分离。

同一个平台连接可以配置多个模型，多个模型也可以分布在不同平台。

### 3.3 配置类

核心配置结构：

```java
@ConfigurationProperties(prefix = "jarvis.llm")
public record LlmProperties(
        String defaultModel,
        Map<String, Connection> connections,
        Map<String, Model> models
) {
    public record Connection(
            String baseUrl,
            String apiKey
    ) {}

    public record Model(
            String connection,
            String modelName,
            Double temperature,
            Integer maxTokens
    ) {}
}
```

注：以上为核心结构示意。模型级参数以当前项目实际代码为准。

## 4. LlmClientFactory

### 4.1 设计目的

避免 ChatService 直接感知不同模型平台的连接配置。

调用方只需要提供模型 ID：

```java
ResolvedLlmClient client = llmClientFactory.getClient(modelId);
```

不传模型 ID 时，使用默认模型：

```java
ResolvedLlmClient client = llmClientFactory.getClient(null);
```

### 4.2 主要处理流程

1. 判断是否指定模型 ID。
2. 未指定时读取 defaultModel。
3. 根据模型 ID 查找 Model 配置。
4. 根据 Model 中的 connection 查找平台配置。
5. 从 WebClient 缓存中获取客户端。
6. 如果缓存不存在，则创建 WebClient。
7. 返回模型名称、参数、平台地址和 WebClient。

### 4.3 缓存设计

WebClient 按 connection ID 缓存。

原因：

- WebClient 本身适合复用。
- 同一个平台下多个模型可以共享连接客户端。
- 避免每次请求重复创建 WebClient。

当前配置属于静态配置。如果后续支持运行时动态修改平台连接信息，需要考虑缓存刷新机制。

## 5. ModelRouter

### 5.1 设计目的

ModelRouter 负责决定本次请求使用哪个模型。

它与 LlmClientFactory 的职责不同：

- ModelRouter：决定使用哪个模型。
- LlmClientFactory：根据模型 ID 获取模型客户端。

### 5.2 模型选择

当前阶段已经实现 ModelRouter，并通过验收。

模型选择应遵循项目中实际实现的优先级规则：

1. 用户请求指定模型时，优先使用指定模型。
2. 未指定时，根据已实现的路由规则选择模型。
3. 没有匹配规则时，回退到默认模型。

具体路由规则以当前 ModelRouter 源码为准。

## 6. SSE 流式响应兼容性研究

本阶段对 MXBC 公司网关和 SiliconFlow 两个平台进行了原始 Chunk 对比。

### 6.1 Chunk 元数据

| 特征 | MXBC 网关 | SiliconFlow |
|---|---|---|
| 首 Chunk 包含 id/model/created | 是 | 是 |
| 中间 Chunk 省略元数据 | 可能 | 未发现 |
| created 全程相同 | 是 | 是 |
| DONE 结束标志 | 是 | 是 |

重要发现：

MXBC 网关可能在中间 Chunk 中省略部分元数据，不能假设每个 Chunk 都包含完整的响应元信息。

### 6.2 思考内容

| 特征 | MXBC 网关 | SiliconFlow |
|---|---|---|
| reasoning_content | 存在 | 存在 |
| 思考内容实时透传 | 曾观察到整段缓冲 | 实时透传 |
| enable_thinking 行为 | 平台相关 | 平台默认行为与 MXBC 不同 |

重要结论：

`enable_thinking` 不应该作为所有模型统一下发的参数。

不同模型平台的思考模式行为可能不同，应当由模型或平台配置决定。

### 6.3 内容字段兼容性

`delta.content` 可能出现 JSON null，而不只是空字符串。

当前解析方式：

```java
return choices.get(0)
        .path("delta")
        .path("content")
        .asText("");
```

可以将 null 转换为空字符串，避免空内容影响流式处理。

### 6.4 Token Usage

两个平台均提供 Token 使用量，但结构不同：

- MXBC：观察到末尾存在独立 usage Chunk。
- SiliconFlow：每个 Chunk 都包含累加的 usage，最后一个 Chunk 的 completion_tokens 为 215。

未来实现 Token 统计时，应当统一转换为内部 Usage 对象，不应将平台原始结构直接暴露给业务层。

建议内部结构：

```java
public record TokenUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {}
```

以上为未来设计建议，不代表当前已经实现 Token 统计。

## 7. 本阶段验收结果

本阶段最终验收 7/7 全部通过。

| # | 验收项 | 结果 |
|---|---|---|
| 1 | ChatService 不再依赖 gatewayWebClient | 通过 |
| 2 | 默认模型 MXBC 流式调用 | 通过 |
| 3 | 多轮会话上下文 | 通过 |
| 4 | sql-analysis Prompt Template | 通过 |
| 5 | MXBC SSE 正常结束 | 通过 |
| 6 | SiliconFlow 第二平台流式调用 | 通过 |
| 7 | 原始 Chunk DEBUG 日志记录 | 通过 |

其中：

- MXBC SSE 连续 4 次正常完成。
- SiliconFlow 使用启动参数切换默认模型，无需修改 YAML。
- 原始 Chunk DEBUG 日志记录 217 条。

此外，ModelRouter 和 ChatRequest.modelId 的后续验收也已由用户确认全部符合预期。

## 8. 本阶段技术决策

### 决策一：继续使用 WebClient

由于公司网关部分 SSE Chunk 缺少 SDK 所需的 id 字段，Spring AI 当前解析链路出现 OpenAIInvalidDataException。

因此当前阶段继续采用 WebClient 直接调用 OpenAI-compatible Chat Completions 接口。

### 决策二：模型平台配置与模型配置分离

平台连接信息和具体模型参数分别管理，降低多平台扩展时的耦合。

### 决策三：模型路由独立于模型客户端工厂

ModelRouter 负责选择，LlmClientFactory 负责创建或获取客户端。

### 决策四：平台差异在适配层处理

不要在业务层假设所有模型平台的 SSE 数据结构、思考模式和 Token Usage 都完全一致。

## 9. 当前阶段边界

本阶段完成了多模型调用与基础模型路由，但尚未实现：

- 基于用户意图的智能模型选择。
- 根据任务复杂度自动选择模型。
- 模型成本与能力评分。
- 模型调用失败后的自动降级。
- Token 使用量持久化与统计。

这些能力应当在后续阶段逐步实现。

## 10. 下一阶段

第五阶段：意图识别（Intent Recognition）。

核心目标：

让 Jarvis 能够判断用户请求属于什么任务，例如：

- 普通技术问答。
- SQL 分析。
- 故障排查。
- 数据查询。

本阶段先理解意图识别与模型路由的区别，再实现简单的第一版 IntentService。

不提前引入复杂 Planner 或 Agent 执行引擎。