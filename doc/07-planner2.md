# 第七阶段：Planner / 任务拆解

## 1. 阶段目标

实现 Jarvis 的任务规划能力，让模型能够将用户提出的复杂目标拆分成多个步骤，并通过后端校验和排序，生成合法的执行计划。

本阶段只负责计划生成、合法性检查和执行顺序计算，不真正执行工具。

## 2. 整体架构

```text
用户目标
   |
   v
PlannerService
   |
   v
模型生成 Plan JSON
   |
   v
ObjectMapper
   |
   v
PlanValidator
   |
   +-- validateUniqueStepIds
   |
   +-- validateDependenciesExist
   |
   +-- validateNoCycles
   |
   v
PlanScheduler
   |
   v
拓扑排序
   |
   v
返回 Plan 和合法执行顺序
```

## 3. Plan 数据结构

### Plan

```java
public record Plan(
        String goal,
        List<PlanStep> steps
) {}
```

### PlanStep

```java
public record PlanStep(
        String stepId,
        String description,
        List<String> dependsOn
) {}
```

字段说明：

- goal：整体任务目标。
- stepId：步骤唯一标识。
- description：步骤描述。
- dependsOn：当前步骤依赖的其他步骤。

当前阶段不在 PlanStep 中加入工具执行参数、执行状态和执行结果。这些内容属于后续执行阶段。

## 4. Planner Prompt

文件：

`src/main/resources/prompts/planner-prompt.md`

主要职责：

1. 理解用户目标。
2. 将复杂任务拆分为明确步骤。
3. 正确描述步骤之间的依赖关系。
4. 不虚构执行结果。
5. 不直接执行任务。
6. 按照约定格式输出 JSON。

Prompt 负责约束模型的规划行为，JSON 结构负责承载规划结果。

## 5. PlannerService

PlannerService 负责：

1. 获取模型客户端。
2. 加载 Planner System Prompt。
3. 发送非流式模型请求。
4. 获取模型生成的 JSON。
5. 使用 Jackson 转换为 Plan。
6. 调用 PlanValidator 校验。
7. 返回合法 Plan。

当前使用 WebClient 和 OpenAI-compatible Chat Completions 接口。

## 6. PlanValidator

PlanValidator 负责确保模型生成的计划结构合法。

### 6.1 唯一性检查

方法：

`validateUniqueStepIds`

检查所有步骤的 stepId 是否重复。

使用 Set 保存已经出现的 stepId。

如果添加失败，说明存在重复 ID。

### 6.2 依赖引用检查

方法：

`validateDependenciesExist`

检查每个 dependsOn 中的步骤 ID 是否真实存在。

使用步骤 ID 集合进行快速判断。

自依赖不在此方法中处理，而是交给循环依赖检测。

### 6.3 循环依赖检查

方法：

`validateNoCycles`

使用 DFS（深度优先搜索）检测有向图中是否存在环。

采用三态标记：

| 状态 | 含义 |
|---|---|
| 0 | 未访问 |
| 1 | 正在访问 |
| 2 | 已访问完成 |

核心判断：

- 遇到状态 1：发现循环依赖。
- 遇到状态 2：已经检查完成，无需重复访问。
- 遇到状态 0：继续递归检查。

自依赖也会被识别为循环依赖。

### 6.4 校验调用顺序

```java
validateUniqueStepIds(plan);
validateDependenciesExist(plan);
validateNoCycles(plan);
```

先保证步骤 ID 唯一，再检查依赖引用，最后进行 DFS。

## 7. PlanScheduler

PlanScheduler 负责根据依赖关系生成合法的步骤执行顺序。

方法：

`topologicalSort(Plan plan)`

采用 Kahn 算法实现拓扑排序。

### 7.1 核心数据结构

| 数据结构 | 用途 |
|---|---|
| Map<String, Integer> inDegree | 保存步骤入度 |
| Map<String, List<String>> dependents | 保存反向依赖关系 |
| Queue<String> queue | 保存入度为 0 的步骤 |
| List<PlanStep> result | 保存最终排序结果 |
| Map<String, PlanStep> stepMap | 根据 ID 获取步骤 |

### 7.2 入度

入度表示当前步骤尚未解除的前置依赖数量。

例如：

```text
step_1: dependsOn = []
step_2: dependsOn = []
step_3: dependsOn = [step_1]
step_4: dependsOn = [step_2, step_3]
```

初始入度：

```text
step_1 = 0
step_2 = 0
step_3 = 1
step_4 = 2
```

### 7.3 反向依赖

原始依赖：

```text
step_3 dependsOn [step_1]
```

反向依赖：

```text
step_1 -> [step_3]
```

这样当 step_1 被处理后，可以快速找到需要解除依赖的后续步骤。

### 7.4 Kahn 算法执行过程

1. 找出所有入度为 0 的步骤，加入队列。
2. 从队列取出一个步骤，加入结果集合。
3. 找出依赖当前步骤的所有后续步骤。
4. 将后续步骤入度减 1。
5. 入度变为 0 时，将步骤加入队列。
6. 重复以上过程，直到队列为空。

### 7.5 循环检测

如果最终结果数量小于 Plan 的步骤总数，说明存在循环依赖。

```java
if (result.size() != plan.steps().size()) {
    throw new IllegalArgumentException(
            "Plan 存在循环依赖，无法完成拓扑排序"
    );
}
```

PlanValidator 和 PlanScheduler 都能够识别循环依赖，但职责不同：

- PlanValidator：检查计划是否合法。
- PlanScheduler：生成执行顺序，并保证排序完整。

## 8. 阶段测试

建议覆盖：

- 正常单步骤计划。
- 多步骤线性依赖。
- 多个无依赖步骤。
- 一个步骤依赖多个步骤。
- 不存在的依赖引用。
- 重复 stepId。
- 自依赖。
- 多节点循环依赖。
- 多个互不连通的依赖子图。
- 空 Plan。

## 9. 本阶段的边界

本阶段已经实现：

- 任务拆解。
- 计划结构转换。
- 计划合法性检查。
- 依赖关系分析。
- 拓扑排序。

尚未实现：

- 真正执行 PlanStep。
- 将上一步结果传给下一步。
- 执行失败后的重试。
- 动态修改计划。
- 执行过程中的状态管理。

这些属于下一阶段 Agent 执行的内容。

## 10. 下一阶段

第八阶段：Agent 执行。

目标是让 Jarvis 不仅能生成计划，还能够真正执行计划中的步骤。

初期先使用已有的 ToolRegistry 和 ToolExecutor，不引入复杂的多 Agent 架构。