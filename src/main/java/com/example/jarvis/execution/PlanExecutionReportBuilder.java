package com.example.jarvis.execution;

import com.example.jarvis.plan.PlanStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 计划执行报告组装器（8.6.3.1）：把 {@link ExecutionContext} 转换为
 * 用户可读的执行报告文本。
 *
 * <p><b>职责非常单一</b>：读取 → 格式化，不做任何决策——不推测失败原因、
 * 不改写结果、不评价格局。报告数据的唯一来源：</p>
 * <ul>
 *   <li>{@code context.summarize()}：整体状态与统计数据</li>
 *   <li>{@code context.getPlanResult().executionOrder()}：按拓扑顺序遍历步骤</li>
 *   <li>{@code context.getStepExecution(stepId)}：每步的状态、结果、失败/跳过原因</li>
 *   <li>{@code PlanStep.description()}：步骤任务描述（Planner 生成）</li>
 * </ul>
 *
 * <p><b>为什么结果是文本报告</b>：ChatResponse 只有 answer 字段，暂不改
 * API 返回结构——把报告组装成字符串放进 answer 直接回复用户。</p>
 *
 * <p><b>敏感信息注意</b>：error 可能含工具参数等敏感内容（8.6.1 注记），
 * 当前原样输出；对外脱敏是后续独立改造，不在本类职责内。</p>
 */
@Component
public class PlanExecutionReportBuilder {

    /**
     * 组装执行报告：整体汇总 + 步骤详情（按拓扑执行顺序）。
     *
     * <p>报告结构：</p>
     * <pre>
     * 任务执行报告
     *
     * 整体状态：部分成功
     * 总步骤：3
     * 成功：1
     * 失败：1
     * 跳过：1
     *
     * 步骤详情：
     *
     * [step_1] SUCCESS
     * 任务：计算 128 × 256
     * 结果：32768
     *
     * [step_2] FAILED
     * 任务：将上一步结果乘以 2
     * 失败原因：工具执行失败
     *
     * [step_3] SKIPPED
     * 任务：查询相关数据
     * 跳过原因：前置依赖步骤未成功
     * </pre>
     *
     * <p>每步骤按状态输出对应行：SUCCESS 输出结果（无产出步骤输出
     * "结果：（无产出）"），FAILED 输出失败原因，SKIPPED 输出跳过原因。
     * 描述来自 PlanStep.description()，结果与原因来自 ExecutionContext，
     * 本类不自行推测。</p>
     *
     * @param context 执行上下文（执行结束后调用；执行中途调用时未终态步骤
     *                按 PENDING/RUNNING 原样展示）
     * @return 用户可读的执行报告
     */
    public String build(ExecutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context 不能为空");
        }

        PlanExecutionSummary summary = context.summarize();
        StringBuilder report = new StringBuilder();

        // ---------- 整体汇总 ----------
        report.append("任务执行报告\n\n")
                .append("整体状态：").append(statusText(summary.status())).append('\n')
                .append("总步骤：").append(summary.totalSteps()).append('\n')
                .append("成功：").append(summary.successCount()).append('\n')
                .append("失败：").append(summary.failedCount()).append('\n')
                .append("跳过：").append(summary.skippedCount()).append('\n');

        // ---------- 步骤详情（按拓扑执行顺序） ----------
        report.append("\n步骤详情：\n");
        List<PlanStep> executionOrder = context.getPlanResult().executionOrder();
        for (PlanStep step : executionOrder) {
            StepExecution execution = context.getStepExecution(step.stepId());
            report.append('\n').append('[').append(step.stepId()).append("] ")
                    .append(execution.status()).append('\n')
                    .append("任务：").append(step.description()).append('\n');

            switch (execution.status()) {
                case SUCCESS -> report.append("结果：")
                        .append(formatResult(execution.result())).append('\n');
                case FAILED -> report.append("失败原因：")
                        .append(formatText(execution.error())).append('\n');
                case SKIPPED -> report.append("跳过原因：")
                        .append(formatText(execution.error())).append('\n');
                // PENDING / RUNNING：执行中途调用时的原样展示，无附加行
                default -> { }
            }
        }

        return report.toString();
    }

    /**
     * 整体状态转中文表述（面向用户，不用枚举原名）。
     */
    private String statusText(PlanExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> "全部成功";
            case PARTIAL_SUCCESS -> "部分成功";
            case FAILED -> "整体失败";
        };
    }

    /**
     * SUCCESS 步骤的结果转文本（null 显示为"（无产出）"）。
     */
    private String formatResult(Object result) {
        return result == null ? "（无产出）" : String.valueOf(result);
    }

    /**
     * 失败/跳过原因兜底：字段缺失时给出明确占位，避免输出 "error: null"。
     */
    private String formatText(String text) {
        return (text == null || text.isBlank()) ? "（未记录原因）" : text;
    }
}
