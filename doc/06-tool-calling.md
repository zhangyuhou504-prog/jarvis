# 第六阶段：Tool Calling（工具调用）

## 1. 阶段目标

本阶段的目标是让 Jarvis 从单纯的 AI 对话系统，升级为能够使用外部工具完成任务的 AI 应用。

此前 Jarvis 只能接收用户问题，将问题发送给大模型，再返回模型生成的回答。

引入 Tool Calling 后，模型可以根据用户问题自主判断是否需要使用工具，并生成工具调用请求。Java 后端负责解析请求、执行对应工具，再将执行结果返回给模型。

核心思想：

**模型负责决策，Java 后端负责执行。**

本阶段采用 OpenAI 兼容的 Chat Completions API，继续使用 WebClient 实现，不引入 Spring AI。

## 2. Tool Calling 核心原理

### 2.1 什么是 Tool Calling

Tool Calling 是一种让大模型调用外部工具的机制。

例如，用户询问：

> 128 × 256 等于多少？

模型可以判断需要使用乘法工具，并返回工具调用请求：

```json
{
  "tool_calls": [
    {
      "id": "call_xxx",
      "type": "function",
      "function": {
        "name": "multiply",
        "arguments": "{\"a\":128,\"b\":256}"
      }
    }
  ]
}
```

注意：

模型并没有真正执行 Java 方法。

它只是告诉应用：
- 想调用哪个工具。
- 需要传递什么参数。
- 本次工具调用的唯一标识是什么。

Java 后端收到请求后，才真正执行对应的业务逻辑。

### 2.2 工具调用完整流程

1. 用户提交问题。
2. 后端将用户问题和工具定义发送给模型。
3. 模型判断是否需要调用工具。
4. 如果需要，模型返回 `tool_calls`。
5. Java 后端解析工具名称和参数。
6. ToolExecutor 找到对应工具并执行。
7. 将执行结果以 `role=tool` 的消息形式返回模型。
8. 模型根据工具执行结果生成最终回答。

整体流程：

```text
用户问题
   |
   v
Java 后端
   |
   v
发送用户消息 + tools 工具定义
   |
   v
大模型判断是否需要工具
   |
   +----------------------+
   |                      |
   v                      v
普通文本回答          返回 tool_calls
   |                      |
   |                      v
   |                 Java 解析调用请求
   |                      |
   |                      v
   |                 ToolExecutor 执行
   |                      |
   |                      v
   |                 返回工具执行结果
   |                      |
   |                      v
   |                 再次请求大模型
   |                      |
   +----------+-----------+
              |
              v
          最终回答
```

## 3. Tool Calling 关键字段

### 3.1 tools

`tools` 用于告诉模型当前有哪些工具可以使用。

一个工具定义包含：
- 工具类型。
- 工具名称。
- 工具用途描述。
- 参数 JSON Schema。

示例：

```json
{
  "type": "function",
  "function": {
    "name": "multiply",
    "description": "计算两个整数的乘积",
    "parameters": {
      "type": "object",
      "properties": {
        "a": {
          "type": "integer",
          "description": "第一个数字"
        },
        "b": {
          "type": "integer",
          "description": "第二个数字"
        }
      },
      "required": ["a", "b"],
      "additionalProperties": false
    }
  }
}
```

这里的 `parameters` 使用 JSON Schema 描述工具参数。

工具定义只是提供给模型的能力说明，不代表 Java 工具已经执行。

### 3.2 tool_choice

用于控制模型如何选择工具。

常见值：

| 值 | 含义 |
|---|---|
| `auto` | 模型自主决定是否调用工具 |
| `none` | 不允许调用工具 |
| `required` | 必须调用工具（需模型服务支持） |

当前使用：

```json
{
  "tool_choice": "auto"
}
```

### 3.3 tool_calls

模型返回的工具调用请求。

其中：
- `id`：本次工具调用的标识。
- `type`：工具类型。
- `function.name`：工具名称。
- `function.arguments`：JSON 格式的参数。

注意：`arguments` 在 OpenAI 兼容接口中通常是 JSON 字符串，需要再次解析。

### 3.4 finish_reason

模型响应中的 `finish_reason` 用于表示本次生成结束的原因。

常见值：

| 值 | 含义 |
|---|---|
| `stop` | 正常生成结束 |
| `length` | 达到生成长度限制 |
| `tool_calls` | 模型请求调用工具 |

`finish_reason=tool_calls` 只表示模型提出了工具调用请求，不代表工具执行成功。

### 3.5 role=tool

Java 执行工具后，需要将结果返回模型。

消息示例：

```json
{
  "role": "tool",
  "tool_call_id": "call_xxx",
  "content": "32768"
}
```

其中 `tool_call_id` 必须与模型此前返回的工具调用 ID 对应。

否则模型无法正确关联工具调用和执行结果。

## 4. MXBC 模型网关能力验证

本阶段首先验证了 MXBC OpenAI 兼容接口是否支持非流式 Tool Calling。

测试请求：
- 接口：`/chat/completions`
- 工具名称：`multiply`
- 参数：`a=128`、`b=256`

模型返回：
- `finish_reason=tool_calls`
- `tool_calls` 数组正常返回。
- 工具名称正确。
- 参数 JSON 正确。
- `tool_call_id` 正常返回。
- `content` 为空。
- `reasoning_content` 包含模型推理内容。

结论：

**已验证 MXBC 模型网关支持非流式 Tool Calling。**

注意：本阶段没有验证 SiliconFlow 平台的 Tool Calling 能力，也没有实现流式 Tool Calling。

## 5. Java 工具实现

### 5.1 MultiplyTool

实现乘法工具。

使用 `BigInteger` 进行乘法运算，避免普通 `int` 或 `long` 计算可能出现的溢出问题。

工具职责：
- 接收两个整数。
- 执行乘法。
- 返回计算结果。

### 5.2 CurrentTimeTool

实现获取当前时间的工具。

支持传入时区，例如：

- `Asia/Shanghai`
- `America/New_York`

通过 Java `ZoneId` 和 `ZonedDateTime` 获取指定时区的当前时间。

配置默认时区：

```yaml
jarvis:
  tool:
    default-timezone: Asia/Shanghai
```

避免直接依赖服务器默认时区，减少不同部署环境造成的时间偏差。

## 6. ToolExecutor

ToolExecutor 负责真正执行工具。

它接收模型返回的工具名称和参数，根据工具名称找到对应的 Java 实现，并执行。

初期采用 `switch` 方式分发工具。

随后随着工具数量增加，引入 ToolRegistry 统一管理工具定义和执行入口。

职责：
- 解析工具参数。
- 找到对应工具。
- 调用 Java 方法。
- 返回执行结果。
- 处理未知工具和工具执行异常。

**需要注意：模型返回的工具名称和参数属于外部输入，不能直接信任。后端必须进行工具白名单校验和参数校验。**

## 7. LlmClientService

负责非流式大模型调用。

主要职责：
- 接收模型请求。
- 通过 WebClient 调用 Chat Completions API。
- 处理 HTTP 响应。
- 返回 JSON 响应对象。

将模型 HTTP 调用能力独立出来，避免 ToolLoopService 直接承担底层 HTTP 通信职责。

## 8. ToolLoopService

ToolLoopService 负责管理完整的工具调用闭环。

主要流程：

1. 复制初始消息列表。
2. 发送模型请求。
3. 判断模型是否返回工具调用。
4. 如果没有工具调用，返回最终回答。
5. 如果存在工具调用，解析工具请求。
6. 调用 ToolExecutor 执行。
7. 将 assistant 工具调用消息加入上下文。
8. 将工具执行结果以 `role=tool` 消息加入上下文。
9. 再次请求模型。
10. 循环直到模型生成最终回答。

### 8.1 多轮调用限制

当前配置：

```yaml
jarvis:
  tool:
    max-rounds: 5
    call-timeout-seconds: 120
```

`max-rounds` 用于限制工具调用循环的轮数，避免模型无限请求工具。

注意：轮数限制不等于工具调用总次数限制。一轮中可能包含多个 `tool_calls`。

后续还需要考虑：
- 每轮最大工具调用数量。
- 单次任务最大工具调用总数。
- 单个工具执行超时。
- 整个任务执行超时。

### 8.2 上下文管理

工具调用过程中，需要将 assistant 的工具调用消息和对应的工具结果消息都保留在上下文中。

工具调用结果通过 `tool_call_id` 与调用请求关联。

当前实现对传入消息进行深拷贝，避免执行过程修改外部传入的原始上下文。

## 9. ToolRegistry 工具注册中心

随着工具数量增加，最初的实现存在一个问题：

- ToolLoopService 维护工具定义。
- ToolExecutor 维护工具执行分发逻辑。

新增工具时需要修改多个地方，容易产生定义和执行不一致的问题。

因此引入 ToolRegistry。

核心目标：

**统一管理工具定义、工具注册和工具执行入口。**

工具实现统一遵循 `JarvisTool` 接口。

工具注册中心负责：
- 收集工具实现。
- 根据工具名称注册工具。
- 获取全部工具定义。
- 根据工具名称查找工具。
- 提供工具执行所需的注册信息。

重构后的关系：

```text
             ToolRegistry
             /          \
            /            \
     工具定义              工具实例
        |                    |
        v                    v
 ToolLoopService         ToolExecutor
        |                    |
        +--------+-----------+
                 |
                 v
             工具执行
```

新增工具时，只需要实现统一接口并注册到容器中，不再需要在多个地方维护重复的工具信息。

## 10. 本阶段验收结果

以下项目均已由实际测试验证通过：

| 验收项 | 结果 |
|---|---|
| MXBC 模型返回 tool_calls | 通过 |
| multiply 工具正常调用 | 通过 |
| getCurrentTime 工具正常调用 | 通过 |
| 模型自主选择不同工具 | 通过 |
| 多轮工具调用 | 通过 |
| 工具结果返回模型 | 通过 |
| 未知工具异常处理 | 通过 |
| 工具执行异常处理 | 通过 |
| 最大循环轮数限制 | 通过 |
| 上下文不被意外修改 | 通过 |
| ToolRegistry 自动注册 | 通过 |
| ToolRegistry 工具发现 | 通过 |
| ToolRegistry 统一执行 | 通过 |
| 新增工具不需要修改 Executor 的 switch | 通过 |

**第六阶段基础 Tool Calling 能力完成。**

## 11. 当前架构能力与限制

### 已具备

- 模型能够自主判断是否需要调用工具。
- 支持多个 Java 工具。
- 支持连续多轮工具调用。
- 支持工具注册和统一管理。
- 支持异常处理和循环次数限制。

### 尚未实现

- 流式 Tool Calling。
- SiliconFlow 平台 Tool Calling 验证。
- MCP Client 接入。
- 工具权限和动态筛选。
- Planner 任务规划。
- Agent 自动执行和任务状态管理。

这些能力留到后续阶段逐步实现。

## 12. 下一阶段：Planner（任务规划与拆解）

Tool Calling 解决的是：

**模型如何调用工具？**

Planner 解决的是：

**面对复杂任务，应该拆分成哪些步骤？**

例如：

用户：

> 查询订单服务最近一小时的错误日志，分析错误最多的类型，并给出可能原因。

未来 Jarvis 可以将任务拆解为：

1. 获取当前时间。
2. 确定最近一小时的时间范围。
3. 查询订单服务错误日志。
4. 统计错误类型及数量。
5. 分析主要错误原因。
6. 生成诊断报告。

下一阶段先实现结构化任务计划：

`用户输入 → PlannerService → 模型生成 JSON 计划 → Java 解析 Plan`

初期只负责生成和校验计划，不立即