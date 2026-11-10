# 03 - Prompt Engineering

## 1. 本阶段目标

让 Jarvis 从简单的模型调用接口，升级为支持角色定义、提示词模板和多轮对话上下文的 AI 应用。

本阶段主要完成：

- System Prompt
- Prompt Template
- Template Registry
- Conversation Context
- 多轮对话历史管理

## 2. System Prompt

System Prompt 用于定义模型的角色、职责和行为规则。

例如：

```text
你是 Jarvis，一个专业的 Java 后端开发助手。

你需要：
1. 解答 Java、Spring Boot、MySQL、Redis、MQ 等技术问题。
2. 分析代码和异常，提供可落地的解决方案。
3. 解释技术原理，而不仅仅给出结论。
4. 不确定的信息明确说明，不要编造事实。
5. 使用简体中文回答。
```

System Prompt 与用户输入不同：

- System Prompt：定义模型应该如何工作。
- User Message：描述用户当前需要解决的问题。

## 3. Prompt Template

Prompt Template 用于将提示词抽象成可复用模板。

例如：

```text
你是 ${role}。

你的任务是：${task}

回答规则：
${rules}
```

不同业务可以使用不同模板：

| Template ID | 用途 |
|---|---|
| default | 通用 Jarvis 助手 |
| java-tech | Java 技术问题 |
| sql-analysis | SQL 性能分析 |

### Template Registry

使用静态注册表管理模板：

```java
private static final Map<String, TemplateDefinition> REGISTRY = Map.of(
    "default", new TemplateDefinition("system-prompt.md", Map.of()),

    "java-tech", new TemplateDefinition("java-tech-template.md", Map.of(
        "role", "Java 后端开发助手",
        "task", "解答 Java 技术问题",
        "rules", "结合 Java 代码解释原理，给出可落地的方案，并说明适用场景与优缺点"
    )),

    "sql-analysis", new TemplateDefinition("sql-analysis-template.md", Map.of(
        "role", "数据库性能分析助手",
        "task", "分析 SQL 慢查询",
        "rules", "重点分析执行计划、索引使用和查询优化建议，必要时给出改写示例"
    ))
);
```

设计原则：

1. 前端只传模板 ID，不直接传任意 System Prompt。
2. 模板内容由后端管理。
3. 未指定模板时使用 default。
4. 未知模板 ID 应明确报错。
5. 不修改注册表中的默认变量，合并变量时创建新的 Map。

## 4. Conversation Context

### 4.1 为什么需要上下文

模型的 Chat Completions 请求通常需要显式携带历史消息。

例如：

```text
user: 什么是 Redis？
assistant: Redis 是一个基于内存的键值数据库。
user: 它为什么适合做缓存？
assistant: Redis 具有低延迟、高性能等特点。
user: 那它有哪些持久化方式？
```

如果只发送最后一条消息，模型无法可靠理解“它”指的是什么。

因此，每轮请求都需要携带必要的历史消息。

### 4.2 消息角色

| Role | 含义 |
|---|---|
| system | 系统提示词 |
| user | 用户消息 |
| assistant | 模型回答 |

### 4.3 会话标识

采用后端生成 UUID 的方式：

```java
String conversationId = UUID.randomUUID().toString();
```

前端创建会话后，保存后端返回的 conversationId。

之后聊天请求持续携带这个 ID。

### 4.4 会话存储

当前阶段使用 JVM 内存：

```java
private final ConcurrentMap<String, Conversation> conversations =
        new ConcurrentHashMap<>();
```

数据结构：

```text
conversations
  ├── conversationId-A
  │     └── messages
  │           ├── user
  │           ├── assistant
  │           ├── user
  │           └── assistant
  │
  └── conversationId-B
        └── messages
```

### 4.5 历史消息快照

读取历史时，复制消息列表，避免请求使用的历史数据受到后续修改影响。

```java
private List<ChatMessage> snapshotHistory(Conversation conversation) {
    synchronized (conversation) {
        List<ChatMessage> all = conversation.getMessages();
        int from = Math.max(0, all.size() - maxHistoryMessages);
        return new ArrayList<>(all.subList(from, all.size()));
    }
}
```

当前实现按消息数量限制历史。若要求严格保留完整问答对，应进一步按轮次裁剪。

### 4.6 保存问答对

模型正常完成后，将用户消息和 assistant 回答一起追加：

```java
private void appendTurn(
        Conversation conversation,
        String userMessage,
        String assistantAnswer) {

    synchronized (conversation) {
        conversation.getMessages().add(
            new ChatMessage("user", userMessage));

        conversation.getMessages().add(
            new ChatMessage("assistant", assistantAnswer));
    }
}
```

当前策略：

- 正常完成：保存完整问答对。
- 模型异常：不保存不完整问答。
- 客户端取消：不保存不完整问答。

## 5. 本阶段技术决策

| 决策 | 当前方案 | 原因 |
|---|---|---|
| 会话 ID | UUID | 简单且具有足够唯一性 |
| 会话存储 | ConcurrentHashMap | 便于学习和验证 |
| 消息结构 | role + content | 满足当前 Chat Completions 场景 |
| 历史读取 | Snapshot | 避免直接使用共享 List |
| 历史裁剪 | 消息条数限制 | 控制上下文规模 |
| 问答保存 | 正常完成后追加 | 避免保存不完整回答 |

## 6. 当前方案的局限

1. JVM 重启后会话丢失。
2. 多实例部署无法共享会话。
3. 同一会话的并发请求尚未实现串行执行。
4. 按消息条数裁剪不等于按 Token 数量裁剪。
5. 尚未实现长期记忆和跨会话信息检索。

这些能力会在后续 Memory 和工程化阶段继续完善。

## 7. 本阶段验收结果

- [x] System Prompt 加载
- [x] Prompt Template
- [x] Template Registry
- [x] 后端创建 conversationId
- [x] 会话查询与清空
- [x] 多轮历史消息组装
- [x] 流式回答完整收集
- [x] 正常完成后保存问答对
- [x] 连续三轮对话验证
- [x] 多会话隔离验证

**阶段结论：Prompt Engineering 与基础 Conversation Context 已完成。**