package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;

import java.util.Locale;
import java.util.Set;

/** 统一维护节点模板来源、功能分类及原生节点默认分类。 */
public final class WorkflowTemplateCatalog {
    public static final Set<String> SOURCES = Set.of("SYSTEM");
    public static final Set<String> CATEGORIES = Set.of(
        "BASIC", "AI", "FLOW_CONTROL", "DATA_TRANSFORM", "TEXT_DOCUMENT", "NETWORK_API",
        "TRIGGER", "NOTIFICATION", "DATA_STORAGE", "MESSAGE_QUEUE"
    );
    private static final String[] MESSAGE_QUEUE_TERMS = {
        "kafka", "rabbitmq", "rabbit mq", "mqtt", "pulsar", "message queue", "event bus", "pubsub", "pub sub", "sqs",
        "消息队列"
    };
    private static final String[] NOTIFICATION_TERMS = {
        "slack", "discord", "telegram", "whatsapp", "microsoft teams", "twilio", "email", "e mail", "sms",
        "notification", "notify", "mailgun", "sendgrid", "通知", "邮件", "短信", "即时消息"
    };
    private static final String[] DATA_STORAGE_TERMS = {
        "database", "postgres", "postgresql", "mysql", "mariadb", "mongodb", "redis", "elasticsearch", "opensearch",
        "snowflake", "clickhouse", "supabase", "dynamodb", "bigquery", "data warehouse", "object storage", "vector store",
        "pinecone", "qdrant", "milvus", "weaviate", "chroma", "s3", "sql", "数据库", "缓存", "数据仓库", "对象存储",
        "向量库"
    };
    private static final String[] TEXT_DOCUMENT_TERMS = {
        "document", "pdf", "docx", "markdown", "ocr", "document extractor", "file extractor", "document parser",
        "text extractor", "knowledge base", "文档", "文本提取", "文件解析", "知识库"
    };
    private static final String[] DATA_TRANSFORM_TERMS = {
        "data transform", "transformer", "converter", "convert", "formatter", "json", "xml", "yaml", "csv", "parser",
        "encode", "decode", "serialize", "deserialize", "aggregate", "数据转换", "格式转换", "编码", "解码"
    };
    private static final String[] AI_TERMS = {
        "openai", "anthropic", "gemini", "cohere", "huggingface", "ollama", "large language model", "llm", "embedding",
        "rerank", "vision model", "speech model", "ai assistant", "人工智能", "大语言模型", "嵌入模型", "重排序"
    };
    private static final String[] NETWORK_API_TERMS = {
        "http", "api", "webhook", "web search", "search engine", "scraper", "scrape", "crawler", "crawl", "browser",
        "fetch", "url", "tavily", "serpapi", "network request", "网络请求", "网页搜索", "网页抓取", "接口调用"
    };

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
            case "LLM", "AGENT", "QUESTION_CLASSIFIER", "PARAMETER_EXTRACTOR", "STRUCTURED_OUTPUT", "RAG", "KNOWLEDGE_RETRIEVAL", "EMBEDDING" -> "AI";
            case "CONDITION", "SWITCH", "ITERATION", "LOOP", "MERGE", "SUB_WORKFLOW", "WAIT" -> "FLOW_CONTROL";
            case "SET_VARIABLE", "JSON_PARSE", "JSON_VALIDATE", "TRANSFORM", "FILTER", "SORT", "AGGREGATE" -> "DATA_TRANSFORM";
            case "TEMPLATE", "CSV", "DOCUMENT_EXTRACTOR" -> "TEXT_DOCUMENT";
            case "HTTP", "TAVILY_TOOL" -> "NETWORK_API";
            case "WEBHOOK_TRIGGER", "SCHEDULE_TRIGGER" -> "TRIGGER";
            case "EMAIL_SEND", "IM_NOTIFY" -> "NOTIFICATION";
            case "SQL_QUERY", "REDIS_COMMAND", "S3_OBJECT", "KNOWLEDGE_UPSERT" -> "DATA_STORAGE";
            case "KAFKA_PUBLISH", "KAFKA_TRIGGER", "RABBITMQ_PUBLISH", "RABBITMQ_TRIGGER" -> "MESSAGE_QUEUE";
            default -> "BASIC";
        };
    }

    /** 使用稳定英文大写编码比较模板目录枚举。 */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
