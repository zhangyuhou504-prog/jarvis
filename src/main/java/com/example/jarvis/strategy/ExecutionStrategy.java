package com.example.jarvis.strategy;

/**
 * 执行策略：本次请求走哪条执行链路（第八阶段 8.2）。
 *
 * <p>不是所有请求都需要拆解计划——"现在几点了"调一次工具就能答，
 * 为它跑一遍 Planner 是浪费延迟和 token。执行策略选择的目的就是：
 * 简单请求走快路径，复杂目标才启动规划链路。</p>
 */
public enum ExecutionStrategy {

    /**
     * 直接执行：普通对话，或单步 Tool Calling。
     * <p>链路：ChatService → ToolLoopService（模型自主决定是否调工具）。
     * 覆盖绝大多数请求——问答、单次计算、单次查询。</p>
     */
    DIRECT,

    /**
     * 规划执行：先拆解再执行。
     * <p>链路：Planner → PlanValidator → PlanScheduler → PlanExecutor
     * （规划出 DAG，按拓扑序逐步执行）。适用于包含多个子任务、
     * 且子任务间存在依赖或顺序关系的复合目标。</p>
     */
    PLANNED
}
