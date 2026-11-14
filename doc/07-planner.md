# 第七阶段：Planner（任务规划与拆解）

## 1. 阶段目标

第六阶段让 Jarvis 学会了"使用工具"——模型能自主判断何时调用 `multiply`、`getCurrentTime`，后端执行后把结果回传。

但工具调用是**单步**的：用户问什么，模型当场调一次工具、当场回答。当用户提出一个**复杂目标**时，单步工具调用不够用：

> 帮我计算 128 × 256，然后把结果乘以 2，再告诉我现在几点了。

这句话包含三个子任务，且第二个依赖第一个的结果。Jarvis 需要先**理解整体目标、拆解成步骤、理清步骤间的依赖**，再逐步执行。

本阶段的目标就是补上这一环：让 Jarvis 具备"先制定计划，再执行计划"的能力。

核心思想：

**Planner 负责规划（拆解目标），Executor 负责执行（按计划跑）。**

本阶段延续既有技术路线：直接调用 OpenAI 兼容的 Chat Completions API，复用 `LlmClientService` 统一调用层，不引入 LangChain4j / Spring AI 等新框架。

## 2. Planner 核心原理

### 2.1 什么是任务规划

任务规划（Planning）是 Agent 系统的基础能力之一。它把用户的自然语言目标，转换成一份结构化的、可执行的步骤清单。

例如用户说：

> 帮我计算 128 × 256，然后把结果乘以 2。

Planner 应生成：

```json
{
  "goal": "计算最终结果",
  "steps": [
    {
      "stepId": "step_1",
      "description": "计算 128 × 256",
      "dependsOn": []
    },
    {
      "stepId": "step_2",
      "description": "将第一步的结果乘以 2",
      "dependsOn": ["step_1"]
    }
  ]
}
```

关键设计：第二步依赖第一步的结果，用 `dependsOn` 表达依赖关系。

这意味着执行器不仅能顺序执行，还能识别哪些步骤**没有依赖、可以并行**。

### 2.2 规划与执行解耦

一个容易走偏的设计是：在计划步骤里直接写死"调用哪个工具、传什么参数"。

本阶段刻意**不这样做**。计划步骤只描述"这一步要完成什么"（自然语言），不绑定具体工具。

原因是职责分离：

- **Planner** 负责理解目标、拆解步骤，不关心 Java 具体怎么执行。
- **Tool Calling** 负责选择和调用工具。

先让 Planner 专注于"规划"这件事，后面再演进，让计划携带更具体的可执行细节。

### 2.3 规划与执行状态分离

计划数据（`Plan` / `PlanStep`）只表达"要做什么、步骤间什么关系"，**不含执行状态**。

`PENDING` / `RUNNING` / `SUCCESS` / `FAILED` 这类运行状态属于执行阶段（PlanExecutor），届时再设计。规划阶段的数据结构保持纯净，职责更清晰。

## 3. 数据结构

### 3.1 Plan

```java
package com.example.jarvis.plan;

public record Plan(
        String goal,
        List<PlanStep> steps
) {
    public Plan {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| goal | String | 用户希望完成的最终目标 |
| steps | List | 任务步骤集合（不可变） |

### 3.2 PlanStep

```java
package com.example.jarvis.plan;

public record PlanStep(
        String stepId,
        String description,
        List<String> dependsOn
) {
    public PlanStep {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| stepId | String | 步骤唯一标识（如 `step_1`） |
| description | String | 当前步骤需要完成什么（自然语言） |
| dependsOn | List | 依赖的步骤 stepId 列表（空 = 无依赖） |

### 3.3 为什么用 Record + 紧凑构造器

- **不可变**：计划一旦生成，字段不应被随意修改。`List.copyOf` 保证集合不可变。
- **构造即合法**：紧凑构造器把"字段非空"这类结构性不变量前置到构造时，非法对象根本造不出来，后续解析校验不用重复处理 null。
- **语义校验后置**：stepId 唯一性、依赖存在性、无环——这些需要看**整个 Plan** 才能判断，单个 record 管不了，交给 `PlanValidator`。

## 4. Planner Prompt

路径：`src/main/resources/prompts/planner-prompt.md`

Prompt 与 JSON Schema 分工明确：

- **Prompt**：告诉模型**如何拆解**任务（规划原则、依赖关系规则、特殊情况处理）。
- **JSON Schema**：告诉模型**必须按什么结构输出**（本阶段暂未接入，后续用 Structured Output 约束）。

Prompt 的核心原则：

1. 准确理解用户最终目标，不擅自改变意图。
2. 按复杂度合理拆解，不过度拆分简单任务。
3. 每个步骤只描述一个明确子任务。
4. 有依赖关系的步骤必须在 `dependsOn` 填写前置 stepId。
5. stepId 唯一，按 `step_1`、`step_2`… 顺序生成。
6. 只负责制定计划，不负责执行，不虚构执行结果。

特殊情况：

- 简单问题只生成一个步骤。
- 目标不明确时，生成"澄清需求"的步骤，不擅自猜测。
- 任务超出系统能力时，在计划中明确体现限制。

## 5. PlannerService

### 5.1 职责

`PlannerService` 只做三件事：

1. 读取规划 Prompt。
2. 调用模型生成计划。
3. 把模型返回的 JSON 解析为 `Plan`。

它不负责执行步骤，也不负责调用 ToolExecutor。

### 5.2 调用链路

```text
用户目标 goal
   |
   v
PlannerService.createPlan(goal)
   |
   +-- 1. LlmClientFactory.getClient(null)   获取默认模型客户端
   |
   +-- 2. LlmClientService.chat(...)          非流式调用，block 等待
   |        system = planner-prompt.md
   |        user   = goal
   |
   +-- 3. 提取 choices[0].message.content
   |
   +-- 4. 容错处理（剥代码块围栏 / goal 缺失回落）
   |
   +-- 5. treeToValue(Plan.class)             解析为 Plan 对象
   |
   v
Plan
```

请求结构与 ChatService 一致：`system`（规划 Prompt）+ `user`（用户目标）两条消息，无历史、无 tools。

### 5.3 健壮性处理

模型不保证 100% 遵守"只输出 JSON"的指令，解析侧做了两层兜底：

- **剥代码块围栏**：模型偶尔会违反指令包一层 ` ```json ... ``` `，解析前剥离。
- **goal 缺失回落**：实测发现模型对简单目标偶尔漏掉 `goal` 字段，此时回落为用户原始目标（计划的 goal 本就是用户目标本身，语义无损）。

JSON 不合法时抛 `IllegalStateException`，由上层决定重试还是向用户报错。

### 5.4 技术要点：Jackson 3

本项目是 Spring Boot 4.x，Spring 层序列化走 **Jackson 3（`tools.jackson`）**，与 `ChatService` / `IntentService` / `ToolExecutor` 保持一致。

Jackson 3 与 2.x 的差异（实现中踩到并已处理）：

- 异常基类是 `tools.jackson.core.JacksonException`（覆盖语法错误 `StreamReadException` 与绑定错误 `DatabindException` 两支），没有 2.x 的 `JsonProcessingException`。
- 把 `JsonNode` 转对象用 `objectMapper.treeToValue(node, Plan.class)`，没有 2.x 的 `readValue(JsonNode, Class)` 重载。

## 6. PlanValidator（计划校验）

### 6.1 为什么需要校验

`Plan` / `PlanStep` 的紧凑构造器只保证**结构性**不变量（字段非空、集合不可变）。但模型生成的 JSON 不保证满足**语义**合法性——stepId 可能重复、依赖可能指向不存在的步骤、依赖可能成环。

这些语义校验需要看整个 Plan 才能判断，由 `PlanValidator` 统一负责。

### 6.2 三个校验方法

| 方法 | 检查内容 | 示例 |
|------|----------|------|
| `validateUniqueStepIds` | stepId 是否重复 | 两个 step 都叫 `step_1` |
| `validateDependenciesExist` | dependsOn 指向的步骤是否存在 | `step_1` 依赖 `step_99` |
| `validateNoCycles` | 依赖关系是否形成环 | `step_1` 依赖自己 |

**调用顺序**有依赖关系，必须按序：

```java
validateUniqueStepIds(plan);
validateDependenciesExist(plan);
validateNoCycles(plan);
```

环检测依赖"依赖引用都存在"这一前提——否则 `stepMap.get(dep)` 为 null 会抛 NPE，而非友好的校验异常。

### 6.3 stepId 唯一性

利用 `Set.add()` 的返回值判断重复：第一次添加返回 `true`，重复添加返回 `false`。无需双重循环，O(n) 完成。

```java
public void validateUniqueStepIds(Plan plan) {
    Set<String> stepIds = new HashSet<>();
    for (PlanStep step : plan.steps()) {
        if (!stepIds.add(step.stepId())) {
            throw new IllegalArgumentException("存在重复的 stepId: " + step.stepId());
        }
    }
}
```

### 6.4 依赖引用存在性

收集所有 stepId 到 Set，再检查每个依赖是否在其中。

**职责边界**：自依赖（`step_1` 依赖 `step_1`）在此检查中**视为通过**——该 stepId 确实存在。它是长度为 1 的环，交给 `validateNoCycles` 以"循环依赖"名义拦截，错误信息更准确。

```java
public void validateDependenciesExist(Plan plan) {
    Set<String> stepIds = plan.steps().stream()
            .map(PlanStep::stepId)
            .collect(Collectors.toSet());
    for (PlanStep step : plan.steps()) {
        for (String dep : step.dependsOn()) {
            if (!stepIds.contains(dep)) {
                throw new IllegalArgumentException(
                        "步骤[" + step.stepId() + "]依赖了不存在的步骤: " + dep);
            }
        }
    }
}
```

### 6.5 循环依赖检测（DFS 三态标记）

核心是 `hasCycle` 辅助方法 + `validateNoCycles` 外层方法。

**三态标记**（`states`：stepId → 状态）：

| 状态 | 含义 |
|------|------|
| 0（缺省） | 未访问 |
| 1 | 正在访问（在当前 DFS 路径上）——再遇到它说明成环 |
| 2 | 已完成（该节点子树已确认无环，无需重复检查） |

```java
private boolean hasCycle(String stepId, Map<String, PlanStep> stepMap, Map<String, Integer> states) {
    Integer state = states.getOrDefault(stepId, 0);
    if (state == 1) {          // 正在访问：发现环
        return true;
    }
    if (state == 2) {          // 已完成：无需重复检查
        return false;
    }
    states.put(stepId, 1);     // 标记为正在访问
    PlanStep step = stepMap.get(stepId);
    for (String dep : step.dependsOn()) {
        if (hasCycle(dep, stepMap, states)) {
            return true;
        }
    }
    states.put(stepId, 2);     // 所有依赖检查完成
    return false;
}
```

外层方法负责遍历**所有**步骤，对尚未访问的节点启动 DFS：

```java
public void validateNoCycles(Plan plan) {
    Map<String, PlanStep> stepMap = plan.steps().stream()
            .collect(Collectors.toMap(PlanStep::stepId, Function.identity()));
    Map<String, Integer> states = new HashMap<>();
    for (PlanStep step : plan.steps()) {
        if (states.getOrDefault(step.stepId(), 0) == 0) {
            if (hasCycle(step.stepId(), stepMap, states)) {
                throw new IllegalArgumentException("存在循环依赖: " + step.stepId());
            }
        }
    }
}
```

三个关键设计：

- **stepMap**：避免每次 DFS 都遍历 List 查找步骤，Map 快速定位。
- **states 共用**：整个 Plan 共用一份状态表，不能每次 DFS 都重建，否则状态 2 无法复用（会重复检查、且可能误判）。
- **for 循环遍历所有节点**：图中可能存在多个**互不连通**的依赖子图，环可能藏在后面某个子图里。只从第一个节点开始 DFS 会漏检。

## 7. 测试与验收

### 7.1 测试分层

| 测试类 | 类型 | 覆盖 |
|--------|------|------|
| `PlanTest` | 单测 | Plan/PlanStep 结构性不变量 |
| `PlannerServiceTest` | 单测（mock 模型） | content → Plan 解析链路、代码块剥离、非法 JSON / 空内容 |
| `PlannerServiceLiveTest` | 联调（真实网关） | 模型真实拆解质量（依赖关系、不过度拆分） |
| `PlanValidatorTest` | 单测 | 三项校验的全部场景 |

### 7.2 真实模型拆解质量（联调验证）

复杂目标"帮我计算 128 × 256，然后把结果乘以 2"：

```text
goal: 帮我计算 128 × 256，然后把结果乘以 2
  step_1 | 计算 128 × 256 的结果。              | dependsOn=[]
  step_2 | 将 step_1 的计算结果乘以 2，得到最终答案。 | dependsOn=[step_1]   ← 依赖正确
```

简单目标"现在几点了？"：

```text
goal: 现在几点了？
  step_1 | 获取当前系统时间并回答用户。          | dependsOn=[]   ← 单步，未过度拆分
```

Prompt 的两条关键原则都验证到：**依赖关系正确表达** + **简单任务不过度拆分**。

### 7.3 计划校验场景

| 场景 | 预期 |
|------|------|
| stepId 唯一 | 通过 |
| 两个 step 都叫 step_1 | 抛"存在重复的 stepId" |
| step_2 依赖 step_1，step_1 存在 | 通过 |
| step_2 依赖 step_99，不存在 | 抛"依赖了不存在的步骤" |
| step_1 依赖 step_1（自依赖） | 引用检查通过，DFS 拦截 |
| step_1 → step_2 → step_3 | 通过 |
| step_1 → step_2 → step_1 | 抛"循环依赖" |
| step_1 → step_1 | 抛"循环依赖" |
| step_1 → step_2，step_3 → step_4（两个无环子图） | 通过 |
| step_1 → step_2，step_3 → step_4 → step_3 | 抛"循环依赖" |

## 8. 阶段进度

| 步骤 | 内容 | 状态 |
|------|------|------|
| 7.1 | Plan / PlanStep 数据结构 | 完成 |
| 7.2 | Planner Prompt | 完成 |
| 7.3 | PlannerService（目标 → Plan） | 完成 |
| 7.4 | 计划解析与校验（PlanValidator） | 完成 |
| 7.5 | PlanExecutor（按步骤执行计划） | 待开始 |
| 7.6 | 接入 Tool Calling（让计划真正调用工具） | 待开始 |

### 8.1 已知问题 / 后续待办

- **校验未接入 PlannerService**：`createPlan` 解析出 Plan 后尚未调用 `PlanValidator`。模型偶发返回空 steps 的计划目前会静默通过。下一步应在校验器上加 `validate(Plan)` 总入口（内部按序调三个方法），并接入 `createPlan` 解析成功之后。
- **JSON Schema 未接入**：当前靠 Prompt 约束输出结构 + 解析侧兜底。后续用 Structured Output（JSON Schema）从模型侧强约束，减少解析失败。
- **模型输出稳定性**：实测抓到过偶发的空 steps、漏 goal 字段。解析侧已兜底，但根治要靠 Structured Output。

## 9. 下一步：7.5 PlanExecutor

PlanExecutor 负责"按步骤执行计划"。需要设计：

- **执行状态**：PENDING / RUNNING / SUCCESS / FAILED（引入到执行层，不污染 Plan 数据结构）。
- **拓扑排序**：基于 `dependsOn` 确定执行顺序，无依赖的步骤可并行。
- **步骤执行**：每个步骤的 description 如何变成一次真实的模型/工具调用（衔接 7.6）。
- **失败处理**：某步失败时，依赖它的后续步骤如何处理（跳过 / 终止 / 重试）。
