package com.example.jarvis.plan;

import java.util.List;

/**
 * 计划步骤：{@link Plan} 中的一个执行步骤。
 *
 * <p><b>依赖关系用 {@code dependsOn} 表达</b>：第二步依赖第一步的结果时，
 * {@code dependsOn=["step_1"]}。这样 PlanExecutor 不仅能顺序执行，
 * 还能识别出无依赖关系的步骤（未来可并行）。</p>
 *
 * <p><b>设计边界（第七阶段 7.1）</b>：只描述"这一步要完成什么"，
 * 不指定用什么工具、传什么参数（规划与工具执行解耦），
 * 也不含执行状态（执行阶段再引入）。</p>
 *
 * <p><b>不变量</b>：stepId、description 非空；dependsOn 不可变（null 归一为空列表，
 * 表示无依赖）。stepId 唯一性、依赖指向合法性、无环等语义校验由计划解析校验环节负责。</p>
 *
 * @param stepId      步骤唯一标识（如 {@code step_1}）
 * @param description 当前步骤需要完成什么（自然语言）
 * @param dependsOn   依赖的步骤 stepId 列表（不可变；空列表 = 无依赖）
 */
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
