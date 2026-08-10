package com.baseai.platform.workflow;

import java.util.Set;

/** 统一维护平台原生工作流节点类型，避免校验、模板和执行器各自维护不一致清单。 */
public final class WorkflowNodeTypes {
    public static final Set<String> ALL = Set.of(
        "START", "END", "LLM", "HTTP", "AGENT", "CONDITION", "ITERATION", "LOOP",
        "SWITCH", "MERGE", "SUB_WORKFLOW", "WAIT",
        "SET_VARIABLE", "TEMPLATE", "JSON_PARSE", "JSON_VALIDATE", "TRANSFORM", "FILTER", "SORT",
        "AGGREGATE", "CSV", "QUESTION_CLASSIFIER", "PARAMETER_EXTRACTOR", "STRUCTURED_OUTPUT",
        "DOCUMENT_EXTRACTOR", "WEBHOOK_TRIGGER", "SCHEDULE_TRIGGER", "EMAIL_SEND", "IM_NOTIFY",
        "SQL_QUERY", "REDIS_COMMAND", "S3_OBJECT", "KAFKA_PUBLISH", "KAFKA_TRIGGER",
        "RABBITMQ_PUBLISH", "RABBITMQ_TRIGGER", "TAVILY_TOOL", "RAG"
    );

    public static final Set<String> NESTED_GRAPH = Set.of("ITERATION", "LOOP");
    public static final Set<String> TRIGGERS = Set.of("WEBHOOK_TRIGGER", "SCHEDULE_TRIGGER", "KAFKA_TRIGGER", "RABBITMQ_TRIGGER");
    public static final Set<String> MARKETPLACE_ONLY = Set.of("TAVILY_TOOL");

    /** 工具类不允许实例化。 */
    private WorkflowNodeTypes() { }
}
