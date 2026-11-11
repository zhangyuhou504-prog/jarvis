package com.example.jarvis.model;

/**
 * Jarvis 支持的意图类型。
 *
 * <p>有限且固定的集合，使用枚举而非字符串：
 * 编译期约束取值、避免拼写不一致（如 "SQL_ANALYSIS" vs "SQL-ANALYSIS"）、
 * 方便后续 switch 分支处理。</p>
 */
public enum IntentType {

    /**
     * 普通知识问答
     */
    GENERAL_QA,

    /**
     * SQL 分析
     */
    SQL_ANALYSIS,

    /**
     * 故障排查
     */
    TROUBLESHOOTING,

    /**
     * 数据查询
     */
    DATA_QUERY,

    /**
     * 无法明确判断
     */
    UNKNOWN
}
