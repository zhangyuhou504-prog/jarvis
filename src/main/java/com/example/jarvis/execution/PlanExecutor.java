package com.example.jarvis.execution;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.plan.PlanResult;
import com.example.jarvis.plan.PlanStep;
import com.example.jarvis.service.ToolLoopResult;
import com.example.jarvis.service.ToolLoopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计划执行器（第八阶段 8.4）：驱动整个计划的执行过程。
 *
 * <p><b>职责分工</b>：</p>
 * <table border="1">
 *   <caption>规划-执行链路各组件职责</caption>
 *   <tr><th>组件</th><th>职责</th></tr>
 *   <tr><td>PlannerService</td><td>拆解任务（目标 → Plan）</td></tr>
 *   <tr><td>PlanValidator</td><td>校验计划合法性</td></tr>
 *   <tr><td>PlanScheduler</td><td>确定执行顺序（拓扑排序）</td></tr>
 *   <tr><td>ExecutionContext</td><td>保存执行状态与结果</td></tr>
 *   <tr><td><b>PlanExecutor（本类）</b></td><td><b>驱动执行过程</b></td></tr>
 *   <tr><td>ToolLoopService</td><td>实际完成模型工具调用闭环</td></tr>
 * </table>
 *
 * <p><b>PlanExecutor 不重新实现 Tool Calling</b>：只负责调度步骤——
 * 按 executionOrder 驱动状态机（PENDING → RUNNING → SUCCESS/FAILED），
 * 每个步骤的具体执行（模型调用、工具选择、多轮工具循环）复用已有的
 * {@code ToolLoopService}。调度与执行分离，与"规划与执行解耦"同一原则。</p>
 *
 * <p><b>结果存储决策（决策点 A）</b>：{@code markSuccess} 保存的是
 * ToolLoopService 的<b>最终文本回答</b>——下游步骤以"前置依赖结果"上下文
 * 的形式消费它，自然语言与步骤描述同构、模型理解连贯。当前版本先跑通
 * Agent 闭环；模型的转述不一定等于工具的真实返回值，后续按真实需求再扩展
 * rawResult/summary 分离（不为未来需求提前增加字段）。</p>
 *
 * <p><b>失败处理决策（决策点 B，8.6 前的当前版本）</b>：单步失败
 * {@code markFailed} 后 <b>continue</b>——不中断 execute，依赖它的下游步骤
 * 由依赖检查自然 SKIPPED（级联），与失败步骤无依赖关系的步骤照常执行
 * （这正是依赖图的价值：一步失败不应无条件导致整个计划失败）。
 * execute 最终返回 ExecutionContext，由调用方根据各步骤状态判断整体成败。
 * 终止/重试等复杂策略放到 8.6。</p>
 */
@Service
@Slf4j
public class PlanExecutor {

    /**
     * 共享 JSON 解析器（Jackson 3，与项目其他组件一致）。
     */
    private final ObjectMapper objectMapper;

    private final ToolLoopService toolLoopService;
    private final LlmClientFactory llmClientFactory;

    /**
     * 步骤执行 Prompt（classpath:prompts/step-execution-prompt.md）。
     */
    private String stepExecutionPrompt;

    public PlanExecutor(
            ObjectMapper objectMapper,
            ToolLoopService toolLoopService,
            LlmClientFactory llmClientFactory,
            @Value("classpath:prompts/step-execution-prompt.md")
            Resource stepExecutionPromptResource) {
        this.objectMapper = objectMapper;
        this.toolLoopService = toolLoopService;
        this.llmClientFactory = llmClientFactory;
        try (InputStream inputStream = stepExecutionPromptResource.getInputStream()) {
            this.stepExecutionPrompt = new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new IllegalStateException("步骤执行 Prompt 加载失败", e);
        }
    }

    /**
     * 执行计划：按拓扑顺序驱动步骤，返回携带全程执行状态的上下文。
     *
     * <p>流程：创建 ExecutionContext（全步骤 PENDING）→ 按
     * {@code planResult.executionOrder()} 遍历 → 依赖守门（不满足则 SKIPPED）
     * → 执行步骤（markRunning → 组装上下文消息 → ToolLoopService 闭环
     * → markSuccess/markFailed）→ 返回上下文。</p>
     *
     * @param planResult 待执行的规划结果（Plan + 拓扑执行顺序）
     * @return 携带每个步骤最终状态与产出的执行上下文
     */
    public ExecutionContext execute(PlanResult planResult) {
        if (planResult == null) {
            throw new IllegalArgumentException("planResult 不能为空");
        }

        // 1. 创建执行上下文：全步骤初始化为 PENDING（构造即完整）
        ExecutionContext context = new ExecutionContext(planResult);

        // 2. 按 executionOrder（拓扑顺序：依赖在前、被依赖在后）遍历步骤。
        //    顺序遍历天然满足依赖约束——轮到某步时其依赖必已处理过
        //    （SUCCESS / FAILED / SKIPPED 三种终态之一）
        for (PlanStep step : planResult.executionOrder()) {
            log.info("执行步骤: stepId={}, dependsOn={}", step.stepId(), step.dependsOn());

            // ---------- 8.4.2 依赖检查 ----------
            // 拓扑顺序只保证"依赖先被处理过"，不保证"依赖都成功"——
            // step_1 失败时，依赖它的 step_2 不应执行。不满足则标记 SKIPPED
            // （级联：SKIPPED ≠ SUCCESS，依赖链下游会因依赖不满足继续被跳过）
            if (!context.areDependenciesSatisfied(step.stepId())) {
                // 记录跳过原因（8.6.1）：具体指出哪些依赖未成功，便于诊断
                String unsatisfied = step.dependsOn().stream()
                        .filter(dep -> {
                            StepExecution depExec = context.getStepExecution(dep);
                            return depExec == null
                                    || depExec.status() != StepExecutionStatus.SUCCESS;
                        })
                        .collect(java.util.stream.Collectors.joining(", "));
                log.info("依赖未满足，跳过步骤: stepId={}, dependsOn={}",
                        step.stepId(), step.dependsOn());
                context.markSkipped(step.stepId(), "前置依赖步骤未成功: " + unsatisfied);
                continue;
            }

            // ---------- 8.4.3 + 8.4.4 步骤执行 ----------
            context.markRunning(step.stepId());
            try {
                // 提取直接依赖结果（走到这里依赖必已 SUCCESS，异常为防御性兜底）
                Map<String, Object> dependencyResults =
                        getDependencyResults(step, context);
                // 组装步骤上下文消息（system + user），交给工具闭环
                ArrayNode messages = buildStepMessages(step, dependencyResults);
                LlmClientFactory.ResolvedLlmClient client =
                        llmClientFactory.getClient(null);
                ToolLoopResult loopResult = toolLoopService.run(client, messages);
                if (loopResult.success()) {
                    // 决策点 A：result 存最终文本回答（rawResult/summary 后续按需扩展）
                    context.markSuccess(step.stepId(), loopResult.answer());
                    log.info("步骤执行成功: stepId={}, 结果长度={}",
                            step.stepId(), loopResult.answer().length());
                } else {
                    // 闭环未成功完成（最终存在未恢复的工具失败 / 轮数上限）：
                    // 步骤 FAILED。失败时模型给出的文本不作为产出，仅随日志留痕
                    context.markFailed(step.stepId(),
                            "存在未恢复的工具调用失败");
                    log.warn("步骤闭环失败: stepId={}, answer={}",
                            step.stepId(), loopResult.answer());
                }
            } catch (RuntimeException e) {
                // 决策点 B：单步失败不中断 execute，下游由依赖检查级联 SKIPPED，
                // 无关步骤照常执行；整体成败由调用方根据状态汇总判断
                // 8.6.1：异常信息记录进 error（可能含敏感参数，对外展示前需脱敏）
                context.markFailed(step.stepId(), e.getMessage());
                log.error("步骤执行失败: stepId={}", step.stepId(), e);
            }
        }

        // 3. 返回执行上下文（每步状态 + 产出 + 整体完成度都在里面）
        return context;
    }

    /**
     * 提取当前步骤的<b>直接依赖</b>步骤的执行结果（8.4.3.1，方案 B：精简上下文）。
     *
     * <p><b>为什么只传直接依赖而不是所有已完成步骤</b>：上下文精简、
     * 无关结果不干扰模型（方案 A 会把 step_1/step_2 的结果也塞给
     * 依赖它们的 step_3 之外的步骤）。职责边界：ExecutionContext 保存
     * <b>所有</b>步骤执行信息；本方法只挑出当前步骤 dependsOn 指向的那部分。</p>
     *
     * <p><b>与 {@code areDependenciesSatisfied} 的区别</b>：那个回答"能不能执行"
     * （boolean，调度守门），本方法回答"执行需要什么数据"（Map，取数）。
     * 职责不同，不合并——调用时序上守门在前、取数在后，走到这里说明依赖
     * 已全部 SUCCESS，本方法的异常是防御性兜底而非正常路径。</p>
     *
     * @param step    当前要执行的步骤
     * @param context 执行上下文
     * @return stepId → 依赖步骤产出（LinkedHashMap，保持 dependsOn 声明顺序）
     * @throws IllegalStateException 依赖步骤未成功（正常路径不会发生：execute 循环
     *                               已用 areDependenciesSatisfied 守门，此异常说明调用顺序错误）
     */
    private Map<String, Object> getDependencyResults(PlanStep step, ExecutionContext context) {
        Map<String, Object> results = new LinkedHashMap<>();

        for (String dependencyId : step.dependsOn()) {
            StepExecution execution = context.getStepExecution(dependencyId);

            if (execution == null
                    || execution.status() != StepExecutionStatus.SUCCESS) {
                throw new IllegalStateException(
                        "依赖步骤尚未成功执行: " + dependencyId
                );
            }

            results.put(dependencyId, execution.result());
        }

        return results;
    }

    /**
     * 依赖结果提取（包私有暴露，仅供同包测试直接验证提取逻辑）。
     */
    Map<String, Object> getDependencyResultsForTest(PlanStep step, ExecutionContext context) {
        return getDependencyResults(step, context);
    }

    /**
     * 组装单个步骤的模型上下文消息（8.4.3.2）：system（步骤执行 Prompt）
     * + user（当前步骤任务 + 前置依赖结果）。
     *
     * <p><b>消息结构</b>：所有依赖结果并入一条 user 消息，而不是拆成多条
     * assistant/tool 消息——依赖结果是"给模型看的执行输入"，不是对话历史；
     * 单条消息语义完整、模型一眼看到"任务 + 全部输入"的对应关系。</p>
     *
     * <p><b>user 消息格式</b>：</p>
     * <pre>
     * 当前步骤任务：将 step_1 和 step_2 的结果相加
     *
     * 前置依赖步骤的结果：
     * - step_1 = 32768
     * - step_2 = 300
     * </pre>
     * 无依赖步骤只有"当前步骤任务"一段。结果值为 Object，经
     * {@code String.valueOf} 转文本（null 显示为"（无产出）"）。
     *
     * <p><b>日志不记录 description</b>：与 execute 循环同一原则，
     * 避免用户敏感信息进入日志。</p>
     *
     * @param step              当前要执行的步骤
     * @param dependencyResults 直接依赖结果（stepId → 产出，来自 getDependencyResults）
     * @return 模型上下文消息（system + user 两条）
     */
    private ArrayNode buildStepMessages(PlanStep step, Map<String, Object> dependencyResults) {
        ArrayNode messages = objectMapper.createArrayNode();

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", stepExecutionPrompt);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", buildUserContent(step, dependencyResults));

        return messages;
    }

    /**
     * 构建 user 消息文本：当前步骤任务 + 前置依赖结果（保持 dependsOn 声明顺序）。
     */
    private String buildUserContent(PlanStep step, Map<String, Object> dependencyResults) {
        StringBuilder content = new StringBuilder();
        content.append("当前步骤任务：").append(step.description());

        if (!dependencyResults.isEmpty()) {
            content.append("\n\n前置依赖步骤的结果：");
            for (Map.Entry<String, Object> entry : dependencyResults.entrySet()) {
                content.append("\n- ").append(entry.getKey()).append(" = ")
                        .append(formatResult(entry.getValue()));
            }
        }
        return content.toString();
    }

    /**
     * 依赖结果转文本：String.valueOf 统一转换，null 显示为"（无产出）"。
     */
    private String formatResult(Object result) {
        return result == null ? "（无产出）" : String.valueOf(result);
    }

    /**
     * 步骤消息组装（包私有暴露，仅供同包测试验证消息结构）。
     */
    ArrayNode buildStepMessagesForTest(PlanStep step, Map<String, Object> dependencyResults) {
        return buildStepMessages(step, dependencyResults);
    }
}
