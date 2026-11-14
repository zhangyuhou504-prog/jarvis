package com.example.jarvis.controller;

import com.example.jarvis.plan.Plan;
import com.example.jarvis.plan.PlanStep;
import com.example.jarvis.plan.PlannerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务规划接口（独立测试用，暂不接入对话主流程）。
 *
 * <p>POST /jarvis/plan，请求 {@code {"goal":"..."}}，返回生成的 {@link Plan}。</p>
 */
@RestController
@RequestMapping("/jarvis/plan")
@RequiredArgsConstructor
public class PlannerController {

    private final PlannerService plannerService;

    /**
     * 根据用户目标生成任务计划。
     *
     * @param request 规划请求
     * @return 任务计划（goal + steps）
     */
    @PostMapping
    public Map<String, Object> createPlan(@Valid @RequestBody PlanCreateRequest request) {
        Plan plan = plannerService.createPlan(request.getGoal());
        return toResponse(request.getGoal(), plan);
    }

    private Map<String, Object> toResponse(String goal, Plan plan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goal", goal);
        body.put("planGoal", plan.goal());
        body.put("steps", plan.steps().stream().map(this::toStep).toList());
        return body;
    }

    private Map<String, Object> toStep(PlanStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stepId", step.stepId());
        map.put("description", step.description());
        map.put("dependsOn", List.copyOf(step.dependsOn()));
        return map;
    }

    /**
     * 规划请求体。
     */
    @Data
    public static class PlanCreateRequest {

        /**
         * 用户希望完成的最终目标。
         */
        @NotBlank(message = "goal 不能为空")
        private String goal;
    }
}
