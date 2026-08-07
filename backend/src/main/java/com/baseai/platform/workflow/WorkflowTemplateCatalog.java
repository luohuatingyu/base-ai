package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;

import java.util.Locale;
import java.util.Set;

/** 统一维护节点模板来源、功能分类及原生节点默认分类。 */
public final class WorkflowTemplateCatalog {
    public static final Set<String> SOURCES = Set.of("SYSTEM", "CUSTOM");
    public static final Set<String> CATEGORIES = Set.of(
        "BASIC", "AI", "FLOW_CONTROL", "DATA_TRANSFORM", "TEXT_DOCUMENT", "NETWORK_API",
        "TRIGGER", "NOTIFICATION", "DATA_STORAGE", "MESSAGE_QUEUE"
    );

    /** 工具类不允许实例化。 */
    private WorkflowTemplateCatalog() { }

    /** 规范模板来源，缺失时按系统模板标记推导原生来源。 */
    public static String source(String value, boolean systemTemplate) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return systemTemplate ? "SYSTEM" : "CUSTOM";
        if (!SOURCES.contains(normalized)) throw new BusinessException("workflow.templateSourceInvalid");
        return normalized;
    }

    /** 规范功能分类，旧客户端未提交时按节点类型推导。 */
    public static String category(String value, String nodeType) {
        String normalized = normalize(value);
        if (normalized.isBlank()) normalized = defaultCategory(nodeType);
        if (!CATEGORIES.contains(normalized)) throw new BusinessException("workflow.templateCategoryInvalid");
        return normalized;
    }

    /** 返回原生节点类型的默认功能分类。 */
    public static String defaultCategory(String nodeType) {
        return switch (normalize(nodeType)) {
            case "START", "END" -> "BASIC";
            case "LLM", "AGENT", "QUESTION_CLASSIFIER", "PARAMETER_EXTRACTOR", "STRUCTURED_OUTPUT" -> "AI";
            case "CONDITION", "SWITCH", "ITERATION", "LOOP", "MERGE", "SUB_WORKFLOW", "WAIT" -> "FLOW_CONTROL";
            case "SET_VARIABLE", "JSON_PARSE", "JSON_VALIDATE", "TRANSFORM", "FILTER", "SORT", "AGGREGATE" -> "DATA_TRANSFORM";
            case "TEMPLATE", "CSV", "DOCUMENT_EXTRACTOR" -> "TEXT_DOCUMENT";
            case "HTTP" -> "NETWORK_API";
            case "WEBHOOK_TRIGGER", "SCHEDULE_TRIGGER" -> "TRIGGER";
            case "EMAIL_SEND", "IM_NOTIFY" -> "NOTIFICATION";
            case "SQL_QUERY", "REDIS_COMMAND", "S3_OBJECT" -> "DATA_STORAGE";
            case "KAFKA_PUBLISH", "KAFKA_TRIGGER", "RABBITMQ_PUBLISH", "RABBITMQ_TRIGGER" -> "MESSAGE_QUEUE";
            default -> "BASIC";
        };
    }

    /** 使用稳定英文大写编码比较模板目录枚举。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
