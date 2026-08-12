package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 在发布和运行前校验节点模板快照与实例覆盖合并后的必填配置。 */
@Component
public class WorkflowNodeConfigValidator {
    private static final Set<String> UNARY_CONDITION_OPERATORS = Set.of("EXISTS", "EMPTY");
    private static final Set<String> SUPPORTED_TYPES = WorkflowNodeTypes.ALL;
    private final ObjectMapper objectMapper;

    /** 注入 JSON 工具以创建隔离的有效配置副本。 */
    public WorkflowNodeConfigValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 返回已显式定义必填规则的节点类型，供测试防止新增节点遗漏。 */
    static Set<String> supportedTypes() { return SUPPORTED_TYPES; }

    /** 汇总主画布及嵌套子画布的配置问题，并以单个业务错误阻止发布。 */
    public void validateForPublish(JsonNode graph, JsonNode templateSnapshots) {
        List<String> errors = new ArrayList<>();
        validateGraph(graph, templateSnapshots, errors);
        if (!errors.isEmpty()) throw new BusinessException("workflow.nodeConfigRequired", String.join("; ", errors));
    }

    /** 在节点参数经表达式解析后复用同一份规则，阻止空值触发外部行为。 */
    static void validateResolved(String type, JsonNode config) {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode value = WorkflowNodeConfigDefaults.withDefaults(objectMapper, type, config);
        LinkedHashSet<String> missing = missingRequirements(type, value);
        if (!missing.isEmpty()) throw new BusinessException("workflow.nodeConfigRequired", type + ": " + String.join(", ", missing));
    }

    /** 校验一层画布节点，并递归检查迭代和循环节点的有效子画布。 */
    private void validateGraph(JsonNode graph, JsonNode templateSnapshots, List<String> errors) {
        graph.path("nodes").forEach(node -> {
            String type = WorkflowGraphValidator.nodeType(node);
            String id = node.path("id").asText();
            ObjectNode config = effectiveConfig(node, templateSnapshots.path(id));
            LinkedHashSet<String> missing = missingRequirements(type, config);
            if (!missing.isEmpty()) errors.add(nodeName(node, id) + " (" + type + "): " + String.join(", ", missing));
            if (WorkflowNodeTypes.NESTED_GRAPH.contains(type) && config.path("bodyGraph").isObject()) {
                validateGraph(config.path("bodyGraph"), objectMapper.createObjectNode(), errors);
            }
        });
    }

    /** 根据节点类型返回缺失或无效的必填配置字段。 */
    private static LinkedHashSet<String> missingRequirements(String rawType, ObjectNode config) {
        String type = rawType == null ? "" : rawType.toUpperCase(Locale.ROOT);
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        if (!SUPPORTED_TYPES.contains(type)) { missing.add("nodeType"); return missing; }
        switch (type) {
            case "START", "END" -> { }
            case "LLM" -> { requireAiModel(type, config, missing); requireText(config, missing, "prompt"); }
            case "HTTP" -> { requireEnum(config, missing, "method", Set.of("GET", "POST", "PUT", "PATCH", "DELETE")); requireText(config, missing, "url"); }
            case "AGENT" -> { requireAiModel(type, config, missing); requireText(config, missing, "prompt"); requireTools(config, missing); }
            case "CONDITION" -> requireCondition(config, missing, "condition");
            case "ITERATION" -> { requireText(config, missing, "collection"); requireObject(config, missing, "bodyGraph"); }
            case "LOOP" -> { requireCondition(config, missing, "condition"); requireObject(config, missing, "bodyGraph"); }
            case "SWITCH" -> { requireCases(config, missing); requireText(config, missing, "defaultBranch"); }
            case "MERGE" -> { requireEnum(config, missing, "mode", Set.of("ARRAY", "OBJECT")); requirePresent(config, missing, "values"); }
            case "SUB_WORKFLOW" -> requireText(config, missing, "workflowCode");
            case "WAIT" -> requireWait(config, missing);
            case "SET_VARIABLE", "TRANSFORM" -> requirePresent(config, missing, "output");
            case "TEMPLATE" -> requireText(config, missing, "template");
            case "JSON_PARSE" -> requirePresent(config, missing, "value");
            case "JSON_VALIDATE", "STRUCTURED_OUTPUT" -> { requirePresent(config, missing, "value"); requireObject(config, missing, "schema"); }
            case "FILTER" -> { requirePresent(config, missing, "collection"); requireCondition(config, missing, "condition"); }
            case "SORT" -> { requirePresent(config, missing, "collection"); requireEnum(config, missing, "direction", Set.of("ASC", "DESC")); }
            case "AGGREGATE" -> { requirePresent(config, missing, "collection"); requireEnum(config, missing, "operation", Set.of("COUNT", "SUM", "AVG", "MIN", "MAX")); }
            case "CSV" -> { requireEnum(config, missing, "operation", Set.of("PARSE", "STRINGIFY")); requirePresent(config, missing, "value"); }
            case "QUESTION_CLASSIFIER" -> { requireAiModel(type, config, missing); requireText(config, missing, "input"); requireCategories(config, missing); }
            case "PARAMETER_EXTRACTOR" -> { requireAiModel(type, config, missing); requireText(config, missing, "input"); requireObject(config, missing, "schema"); }
            case "DOCUMENT_EXTRACTOR" -> requireDocument(config, missing);
            case "WEBHOOK_TRIGGER", "IM_NOTIFY" -> requirePositive(config, missing, "connectionId");
            case "SCHEDULE_TRIGGER" -> requireText(config, missing, "cron");
            case "EMAIL_SEND" -> { requirePositive(config, missing, "routeId"); requireText(config, missing, "subject"); if (text(config, "subject").contains("\n") || text(config, "subject").contains("\r")) missing.add("subject"); }
            case "SQL_QUERY" -> { requirePositive(config, missing, "connectionId"); requireText(config, missing, "query"); }
            case "REDIS_COMMAND" -> { requirePositive(config, missing, "connectionId"); requireEnum(config, missing, "command", Set.of("GET", "SET", "DEL", "HGET", "HSET", "LPUSH", "RPUSH", "LRANGE", "PUBLISH")); requireArray(config, missing, "arguments", redisArgumentMinimum(config.path("command").asText())); }
            case "S3_OBJECT" -> requireS3(config, missing);
            case "KAFKA_PUBLISH" -> { requirePositive(config, missing, "connectionId"); requireText(config, missing, "topic"); requirePresent(config, missing, "value"); }
            case "KAFKA_TRIGGER" -> { requirePositive(config, missing, "connectionId"); requireText(config, missing, "topic"); }
            case "RABBITMQ_PUBLISH" -> { requirePositive(config, missing, "connectionId"); requireRabbitDestination(config, missing); requirePresent(config, missing, "value"); }
            case "RABBITMQ_TRIGGER" -> { requirePositive(config, missing, "connectionId"); requireText(config, missing, "queue"); }
            case "TAVILY_TOOL" -> requireTavily(config, missing);
            case "PLUGIN_ACTION", "PLUGIN_TRIGGER", "PLUGIN_MODEL", "PLUGIN_DATASOURCE", "PLUGIN_AGENT_STRATEGY", "PLUGIN_EXTENSION" -> {
                requirePositive(config, missing, "pluginComponentId");
                requireText(config, missing, "packageFingerprint");
                requireText(config, missing, "componentExternalId");
                requireObject(config, missing, "parameters");
            }
            case "RAG" -> { requireAiModel(type,config,missing);requirePositive(config,missing,"knowledgeBaseId");requireText(config,missing,"query");requireIntegerRange(config,missing,"topK",1,50);if(!config.has("scoreThreshold")||!config.path("scoreThreshold").isNumber()||config.path("scoreThreshold").asDouble()<0||config.path("scoreThreshold").asDouble()>1)missing.add("scoreThreshold"); }
            case "KNOWLEDGE_RETRIEVAL" -> { requirePositive(config,missing,"knowledgeBaseId");requireText(config,missing,"query");requireIntegerRange(config,missing,"topK",1,50);if(!config.has("scoreThreshold")||!config.path("scoreThreshold").isNumber()||config.path("scoreThreshold").asDouble()<0||config.path("scoreThreshold").asDouble()>1)missing.add("scoreThreshold"); }
            case "KNOWLEDGE_UPSERT" -> { requirePositive(config,missing,"knowledgeBaseId");requireDocument(config,missing);requireText(config,missing,"fileName");requireText(config,missing,"contentType"); }
            case "EMBEDDING" -> { requireAiModel(type,config,missing);requireEmbeddingInput(config,missing); }
            default -> missing.add("nodeType");
        }
        return missing;
    }

    /** 校验模型路由或指定模型二选一方案。 */
    private static void requireAiModel(String nodeType, ObjectNode config, Set<String> missing) {
        String mode = text(config, "modelMode").toUpperCase(Locale.ROOT);
        if ("ROUTE".equals(mode)) { requireText(config, missing, "featureCode"); requireText(config, missing, "modelType"); }
        else if ("DIRECT".equals(mode)) requirePositive(config, missing, "modelId");
        else missing.add("modelMode");
        if (!WorkflowModelCompatibility.supports(nodeType, text(config, "modelType"))) missing.add("modelType");
    }

    /** 校验向量化输入为单个短文本或受限短文本数组。 */
    private static void requireEmbeddingInput(ObjectNode config, Set<String> missing) {
        JsonNode input = config.path("input");
        if (input.isTextual()) {
            String value = input.asText().trim();
            if (value.isBlank() || value.length() > 500) missing.add("input");
            return;
        }
        if (!input.isArray() || input.isEmpty() || input.size() > 256) { missing.add("input"); return; }
        for (JsonNode item : input) {
            if (!item.isTextual() || item.asText().trim().isBlank() || item.asText().trim().length() > 500) {
                missing.add("input"); return;
            }
        }
    }

    /** 校验等待单位和对应的正数时长。 */
    private static void requireWait(ObjectNode config, Set<String> missing) {
        String mode = text(config, "durationMode").toUpperCase(Locale.ROOT);
        if ("SECONDS".equals(mode)) requirePositive(config, missing, "seconds");
        else if ("MILLISECONDS".equals(mode)) requirePositive(config, missing, "milliseconds");
        else missing.add("durationMode");
    }

    /** 校验文档文本或 Base64 输入方案。 */
    private static void requireDocument(ObjectNode config, Set<String> missing) {
        String mode = text(config, "inputMode").toUpperCase(Locale.ROOT);
        if ("TEXT".equals(mode)) requireText(config, missing, "content");
        else if ("BASE64".equals(mode)) requireBase64(config, missing, "base64", true);
        else missing.add("inputMode");
    }

    /** 校验 S3 操作与 PUT 内容来源方案。 */
    private static void requireS3(ObjectNode config, Set<String> missing) {
        requirePositive(config, missing, "connectionId");
        String operation = text(config, "operation").toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "PUT", "LIST", "DELETE").contains(operation)) { missing.add("operation"); return; }
        if (!"LIST".equals(operation)) requireText(config, missing, "key");
        if (!"PUT".equals(operation)) return;
        String mode = text(config, "contentMode").toUpperCase(Locale.ROOT);
        if ("TEXT".equals(mode)) requirePresent(config, missing, "content");
        else if ("BASE64".equals(mode)) requireBase64(config, missing, "base64", false);
        else missing.add("contentMode");
    }

    /** 校验 RabbitMQ 自定义交换机或默认交换机方案。 */
    private static void requireRabbitDestination(ObjectNode config, Set<String> missing) {
        String mode = text(config, "destinationMode").toUpperCase(Locale.ROOT);
        if ("EXCHANGE".equals(mode)) requireText(config, missing, "exchange");
        else if ("DEFAULT_EXCHANGE".equals(mode)) requireText(config, missing, "routingKey");
        else missing.add("destinationMode");
    }

    /** 校验 Tavily Search/Extract 的连接、操作和关键官方参数边界。 */
    private static void requireTavily(ObjectNode config, Set<String> missing) {
        requirePositive(config, missing, "connectionId");
        String operation = text(config, "operation").toUpperCase(Locale.ROOT);
        if ("SEARCH".equals(operation)) {
            requireText(config, missing, "query");
            requireEnum(config, missing, "searchDepth", Set.of("BASIC", "ADVANCED", "FAST", "ULTRA-FAST"));
            requireIntegerRange(config, missing, "maxResults", 1, 20);
        } else if ("EXTRACT".equals(operation)) {
            requireText(config, missing, "urls");
            requireEnum(config, missing, "extractDepth", Set.of("BASIC", "ADVANCED"));
            requireEnum(config, missing, "format", Set.of("MARKDOWN", "TEXT"));
        } else missing.add("operation");
    }

    /** 校验 Switch 的分支名称和条件均可执行。 */
    private static void requireCases(ObjectNode config, Set<String> missing) {
        JsonNode cases = config.path("cases");
        if (!cases.isArray() || cases.isEmpty()) { missing.add("cases"); return; }
        for (JsonNode item : cases) {
            if (item == null || !item.isObject() || item.path("branch").asText().trim().isBlank()) { missing.add("cases"); return; }
            LinkedHashSet<String> conditionMissing = new LinkedHashSet<>();
            requireConditionObject(item.path("condition"), conditionMissing);
            if (!conditionMissing.isEmpty()) { missing.add("cases"); return; }
        }
    }

    /** 校验 Agent 工具名称、类型和对应目标。 */
    private static void requireTools(ObjectNode config, Set<String> missing) {
        JsonNode tools = config.path("tools");
        if (!tools.isArray() || tools.isEmpty()) { missing.add("tools"); return; }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText().trim();
            String type = tool.path("toolType").asText().trim().toUpperCase(Locale.ROOT);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}") || !names.add(name)) { missing.add("tools"); return; }
            if ("HTTP".equals(type) && !tool.path("config").path("url").asText().trim().isBlank()) continue;
            if ("WORKFLOW".equals(type) && !tool.path("workflowCode").asText().trim().isBlank()) continue;
            missing.add("tools"); return;
        }
    }

    /** 校验问题分类至少有两个名称唯一的候选项。 */
    private static void requireCategories(ObjectNode config, Set<String> missing) {
        JsonNode categories = config.path("categories");
        if (!categories.isArray() || categories.size() < 2) { missing.add("categories"); return; }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode category : categories) {
            String name = category.path("name").asText().trim();
            if (name.isBlank() || !names.add(name)) { missing.add("categories"); return; }
        }
    }

    /** 合并版本中的模板快照和画布实例覆盖，保持与执行阶段一致。 */
    private ObjectNode effectiveConfig(JsonNode node, JsonNode snapshot) {
        ObjectNode result = objectMapper.createObjectNode();
        if (snapshot.path("config").isObject()) result.setAll((ObjectNode) snapshot.path("config").deepCopy());
        JsonNode own = node.path("config").isObject() ? node.path("config") : node.path("data").path("config");
        if (own.isObject()) deepMerge(result, own);
        return WorkflowNodeConfigDefaults.withDefaults(objectMapper, WorkflowGraphValidator.nodeType(node), result);
    }

    /** 递归合并对象覆盖，避免实例只修改嵌套字段时丢失模板默认值。 */
    private void deepMerge(ObjectNode target, JsonNode source) {
        source.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject() && target.path(entry.getKey()).isObject()) deepMerge((ObjectNode) target.path(entry.getKey()), entry.getValue());
            else target.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }

    /** 返回节点显示名称，缺省时回退稳定节点 ID。 */
    private String nodeName(JsonNode node, String id) {
        String label = node.path("data").path("label").asText().trim();
        return label.isBlank() ? id : label;
    }

    /** 校验字段在配置对象中存在，显式 null 仍视为有效业务值。 */
    private static void requirePresent(ObjectNode config, Set<String> missing, String key) { if (!config.has(key)) missing.add(key); }

    /** 校验字段为非空文本。 */
    private static void requireText(ObjectNode config, Set<String> missing, String key) { if (text(config, key).isBlank()) missing.add(key); }

    /** 校验字段为正数连接、路由、模型或时长标识。 */
    private static void requirePositive(ObjectNode config, Set<String> missing, String key) { if (!config.hasNonNull(key) || config.path(key).asLong() <= 0) missing.add(key); }

    /** 校验整数参数位于闭区间内。 */
    private static void requireIntegerRange(ObjectNode config, Set<String> missing, String key, int minimum, int maximum) {
        JsonNode value = config.path(key);
        if (!value.isIntegralNumber() || value.asInt() < minimum || value.asInt() > maximum) missing.add(key);
    }

    /** 校验字段为 JSON 对象。 */
    private static void requireObject(ObjectNode config, Set<String> missing, String key) { if (!config.path(key).isObject()) missing.add(key); }

    /** 校验字段为达到最少元素数量的 JSON 数组。 */
    private static void requireArray(ObjectNode config, Set<String> missing, String key, int minimum) { if (!config.path(key).isArray() || config.path(key).size() < minimum) missing.add(key); }

    /** 校验结构化条件包含左值、操作符及非一元操作所需右值。 */
    private static void requireCondition(ObjectNode config, Set<String> missing, String key) { requireConditionObject(config.path(key), missing); }

    /** 校验任意条件对象的完整性。 */
    private static void requireConditionObject(JsonNode condition, Set<String> missing) {
        String operator = condition.path("operator").asText("EQ").toUpperCase(Locale.ROOT);
        if (!condition.isObject() || condition.path("left").asText().trim().isBlank()
            || !Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "CONTAINS", "EXISTS", "EMPTY").contains(operator)
            || !UNARY_CONDITION_OPERATORS.contains(operator) && !condition.has("right")) missing.add("condition");
    }

    /** 校验枚举方案值，拒绝依赖默认值的模糊行为。 */
    private static void requireEnum(ObjectNode config, Set<String> missing, String key, Set<String> values) { if (!values.contains(text(config, key).toUpperCase(Locale.ROOT))) missing.add(key); }

    /** 校验必传 Base64 字段，并允许表达式在运行时再解析。 */
    private static void requireBase64(ObjectNode config, Set<String> missing, String key, boolean nonEmpty) {
        if (!config.has(key) || (nonEmpty && text(config, key).isBlank())) { missing.add(key); return; }
        String value = text(config, key);
        if (value.contains("{{") && value.contains("}}")) return;
        try { Base64.getDecoder().decode(value); } catch (IllegalArgumentException exception) { missing.add(key); }
    }

    /** 返回 Redis 白名单命令执行所需的最少参数数量。 */
    private static int redisArgumentMinimum(String command) {
        return switch ((command == null ? "GET" : command).toUpperCase(Locale.ROOT)) {
            case "SET", "HGET", "LPUSH", "RPUSH", "PUBLISH" -> 2;
            case "HSET", "LRANGE" -> 3;
            default -> 1;
        };
    }

    /** 安全读取配置文本。 */
    private static String text(ObjectNode config, String key) { return config.path(key).asText("").trim(); }
}
