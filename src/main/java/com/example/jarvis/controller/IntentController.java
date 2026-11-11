package com.example.jarvis.controller;

import com.example.jarvis.model.IntentResult;
import com.example.jarvis.service.IntentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 意图识别接口（独立测试用，暂不接入 ChatService 主流程）。
 *
 * <p>POST /jarvis/intent，请求 {@code {"input":"..."}}，返回 {@link IntentResult}。</p>
 */
@RestController
@RequestMapping("/jarvis/intent")
@RequiredArgsConstructor
public class IntentController {

    private final IntentService intentService;

    /**
     * 识别意图。
     *
     * @param request 意图识别请求
     * @return 意图识别结果（intent/confidence/reason）
     */
    @PostMapping
    public Map<String, Object> recognize(@Valid @RequestBody IntentRecognizeRequest request) {
        IntentResult result = intentService.recognize(request.getInput());
        return Map.of(
                "input", request.getInput(),
                "intent", result.intent().name(),
                "confidence", result.confidence(),
                "reason", result.reason()
        );
    }

    /**
     * 意图识别请求体。
     */
    @Data
    public static class IntentRecognizeRequest {

        /**
         * 用户输入。
         */
        @NotBlank(message = "input 不能为空")
        private String input;
    }
}
