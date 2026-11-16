package com.example.jarvis.service;

/**
 * 工具调用闭环的执行结果（8.4.3.3）：在最终回答之外，同时告知调用方
 * 本次闭环是否成功完成。
 *
 * <p><b>字段含义</b>：</p>
 * <ul>
 *   <li>{@code answer}：模型最终回答。闭环失败时可能是对失败的说明文本，
 *       也可能为空——调用方不应在失败时把它当有效产出使用</li>
 *   <li>{@code success}：本次工具调用闭环是否成功完成</li>
 * </ul>
 *
 * <p><b>成败判定规则（恢复模式）</b>：</p>
 * <table border="1">
 *   <caption>执行过程 → 最终状态</caption>
 *   <tr><th>执行过程</th><th>success</th></tr>
 *   <tr><td>第一次工具调用成功</td><td>true</td></tr>
 *   <tr><td>第一次失败，第二次重试成功</td><td>true</td></tr>
 *   <tr><td>多次调用，最终工具调用失败</td><td>false</td></tr>
 *   <tr><td>没有调用工具，模型直接回答</td><td>true（暂按当前设计）</td></tr>
 *   <tr><td>达到工具调用轮数上限</td><td>false</td></tr>
 * </table>
 *
 * <p><b>核心判据</b>：不是统计历史上有没有发生过异常，而是判断
 * <b>最终是否存在未恢复的工具失败</b>——中途失败但被模型恢复
 * （改用其他方式完成、或重试成功）不算失败。</p>
 *
 * <p><b>与 PlanExecutor 的对接</b>：success=true → 步骤 SUCCESS（answer 作为
 * 步骤产出）；success=false → 步骤 FAILED（失败说明不作为产出）。
 * 失败时模型仍给出的文本不丢弃（answer 携带），便于上层诊断与日志。</p>
 */
public record ToolLoopResult(
        String answer,
        boolean success
) {
}
