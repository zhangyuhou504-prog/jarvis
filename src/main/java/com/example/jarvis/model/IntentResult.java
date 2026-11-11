package com.example.jarvis.model;

/**
 * 意图识别结果。
 *
 * <p>数据载体：保存意图识别的结构化输出，不含业务行为。
 * 后续由 IntentService 从模型返回的 JSON 反序列化为本对象。</p>
 *
 * @param intent     识别出的意图类型
 * @param confidence 模型给出的置信度（0~1）。注意：这是模型自我评估，
 *                   不是校准概率，v1 仅用于日志和调试，不用于路由开关
 * @param reason     识别理由（便于调试和后续评估）
 */
public record IntentResult(

        IntentType intent,

        Double confidence,

        String reason

) {}
