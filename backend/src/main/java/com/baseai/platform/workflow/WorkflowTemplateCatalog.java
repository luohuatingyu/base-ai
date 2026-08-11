package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;

import java.util.Locale;
import java.util.Set;

/** 统一维护节点模板来源、功能分类及原生节点默认分类。 */
public final class WorkflowTemplateCatalog {
    public static final Set<String> SOURCES = Set.of("SYSTEM", "N8N", "DIFY");
    public static final Set<String> CATEGORIES = Set.of(
        "BASIC", "AI", "FLOW_CONTROL", "DATA_TRANSFORM", "TEXT_DOCUMENT", "NETWORK_API",
        "TRIGGER", "NOTIFICATION", "DATA_STORAGE", "MESSAGE_QUEUE"
    );

    /** 工具类不允许实例化。 */
    private WorkflowTemplateCatalog() { }

    /** 规范模板来源，旧客户端未提交时兼容为系统来源。 */
    public static String source(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return "SYSTEM";
        if (!SOURCES.contains(normalized)) throw new BusinessException("workflow.templateSourceInvalid");
        return normalized;
    }

    /** 更新时省略来源则保留已配置值，避免旧客户端覆盖目录元数据。 */
    public static String updatedSource(String value, String existing) {
        return normalize(value).isBlank() ? source(existing) : source(value);
    }

    /** 规范功能分类，旧客户端未提交时按节点类型推导。 */
    public static String category(String value, String nodeType) {
        String normalized = normalize(value);
        if (normalized.isBlank()) normalized = defaultCategory(nodeType);
        if (!CATEGORIES.contains(normalized)) throw new BusinessException("workflow.templateCategoryInvalid");
        return normalized;
    }

    /** 更新时省略分类则保留已配置值，避免旧客户端重置管理员调整。 */
    public static String updatedCategory(String value, String existing, String nodeType) {
        return normalize(value).isBlank() ? category(existing, nodeType) : category(value, nodeType);
    }

    /** 返回原生节点类型的默认功能分类。 */
    public static String defaultCategory(String nodeType) {
        return switch (normalize(nodeType)) {
            case "START", "END" -> "BASIC";
            case "LLM", "AGENT", "QUESTION_CLASSIFIER", "PARAMETER_EXTRACTOR", "STRUCTURED_OUTPUT", "RAG", "KNOWLEDGE_RETRIEVAL", "EMBEDDING", "PLUGIN_MODEL", "PLUGIN_AGENT_STRATEGY" -> "AI";
            case "CONDITION", "SWITCH", "ITERATION", "LOOP", "MERGE", "SUB_WORKFLOW", "WAIT" -> "FLOW_CONTROL";
            case "SET_VARIABLE", "JSON_PARSE", "JSON_VALIDATE", "TRANSFORM", "FILTER", "SORT", "AGGREGATE" -> "DATA_TRANSFORM";
            case "TEMPLATE", "CSV", "DOCUMENT_EXTRACTOR" -> "TEXT_DOCUMENT";
            case "HTTP", "TAVILY_TOOL", "PLUGIN_ACTION", "PLUGIN_EXTENSION" -> "NETWORK_API";
            case "WEBHOOK_TRIGGER", "SCHEDULE_TRIGGER", "PLUGIN_TRIGGER" -> "TRIGGER";
            case "EMAIL_SEND", "IM_NOTIFY" -> "NOTIFICATION";
            case "SQL_QUERY", "REDIS_COMMAND", "S3_OBJECT", "KNOWLEDGE_UPSERT", "PLUGIN_DATASOURCE" -> "DATA_STORAGE";
            case "KAFKA_PUBLISH", "KAFKA_TRIGGER", "RABBITMQ_PUBLISH", "RABBITMQ_TRIGGER" -> "MESSAGE_QUEUE";
            default -> "BASIC";
        };
    }

    /** 使用稳定英文大写编码比较模板目录枚举。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
