package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 在工作流发布前校验节点模板快照与实例覆盖合并后的必填配置。 */
@Component
public class WorkflowNodeConfigValidator {
    private static final Set<String> UNARY_CONDITION_OPERATORS = Set.of("EXISTS", "EMPTY");
    private final ObjectMapper objectMapper;

    /** 注入 JSON 工具以创建隔离的有效配置副本。 */
    public WorkflowNodeConfigValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 汇总主画布及嵌套子画布的配置问题，并以单个业务错误阻止发布。 */
    public void validateForPublish(JsonNode graph, JsonNode templateSnapshots) {
        List<String> errors = new ArrayList<>();
        validateGraph(graph, templateSnapshots, errors);
        if (!errors.isEmpty()) throw new BusinessException("workflow.nodeConfigRequired", String.join("；", errors));
    }

    /** 校验一层画布节点，并递归检查迭代和循环节点的有效子画布。 */
    private void validateGraph(JsonNode graph, JsonNode templateSnapshots, List<String> errors) {
        graph.path("nodes").forEach(node -> {
            String type = WorkflowGraphValidator.nodeType(node);
            String id = node.path("id").asText();
            ObjectNode config = effectiveConfig(node, templateSnapshots.path(id));
            LinkedHashSet<String> missing = missingRequirements(type, config);
            if (!missing.isEmpty()) errors.add(nodeName(node, id) + "（" + type + "）：" + String.join(", ", missing));
            if (WorkflowNodeTypes.NESTED_GRAPH.contains(type) && config.path("bodyGraph").isObject()) {
                validateGraph(config.path("bodyGraph"), objectMapper.createObjectNode(), errors);
            }
        });
    }

    /** 根据节点类型返回缺失或无效的必填配置字段。 */
    private LinkedHashSet<String> missingRequirements(String type, ObjectNode config) {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        switch (type) {
            case "HTTP" -> requireText(config, missing, "url");
            case "AGENT" -> requireArray(config, missing, "tools", 1);
            case "CONDITION" -> requireCondition(config, missing, "condition");
            case "ITERATION" -> { requireText(config, missing, "collection"); requireObject(config, missing, "bodyGraph"); }
            case "LOOP" -> { requireCondition(config, missing, "condition"); requireObject(config, missing, "bodyGraph"); }
            case "SWITCH" -> requireArray(config, missing, "cases", 1);
            case "MERGE" -> requirePresent(config, missing, "values");
            case "SUB_WORKFLOW" -> requireText(config, missing, "workflowCode");
            case "SET_VARIABLE", "TRANSFORM" -> requirePresent(config, missing, "output");
            case "TEMPLATE" -> requireText(config, missing, "template");
            case "JSON_PARSE", "CSV" -> requirePresent(config, missing, "value");
            case "JSON_VALIDATE", "STRUCTURED_OUTPUT" -> {
                requirePresent(config, missing, "value"); requireObject(config, missing, "schema");
            }
            case "FILTER" -> { requirePresent(config, missing, "collection"); requireCondition(config, missing, "condition"); }
            case "SORT", "AGGREGATE" -> requirePresent(config, missing, "collection");
            case "QUESTION_CLASSIFIER" -> {
                requireText(config, missing, "input"); requireArray(config, missing, "categories", 2);
            }
            case "PARAMETER_EXTRACTOR" -> { requireText(config, missing, "input"); requireObject(config, missing, "schema"); }
            case "DOCUMENT_EXTRACTOR" -> requireOneText(config, missing, "contentOrBase64", "content", "base64");
            case "WEBHOOK_TRIGGER", "IM_NOTIFY", "SQL_QUERY", "REDIS_COMMAND", "S3_OBJECT",
                 "KAFKA_PUBLISH", "KAFKA_TRIGGER", "RABBITMQ_PUBLISH", "RABBITMQ_TRIGGER" ->
                requirePositive(config, missing, "connectionId");
            case "EMAIL_SEND" -> requirePositive(config, missing, "routeId");
            case "SCHEDULE_TRIGGER" -> requireText(config, missing, "cron");
            default -> { }
        }
        validateConditionalRequirements(type, config, missing);
        return missing;
    }

    /** 校验依赖节点操作、命令或字段组合的条件必填项。 */
    private void validateConditionalRequirements(String type, ObjectNode config, LinkedHashSet<String> missing) {
        if ("SQL_QUERY".equals(type)) requireText(config, missing, "query");
        if ("REDIS_COMMAND".equals(type)) requireArray(config, missing, "arguments", redisArgumentMinimum(config.path("command").asText()));
        if ("S3_OBJECT".equals(type) && !"LIST".equalsIgnoreCase(config.path("operation").asText("GET"))) {
            requireText(config, missing, "key");
        }
        if ("KAFKA_PUBLISH".equals(type) || "KAFKA_TRIGGER".equals(type)) requireText(config, missing, "topic");
        if ("KAFKA_PUBLISH".equals(type) || "RABBITMQ_PUBLISH".equals(type)) requirePresent(config, missing, "value");
        if ("RABBITMQ_TRIGGER".equals(type)) requireText(config, missing, "queue");
        if ("RABBITMQ_PUBLISH".equals(type)
            && text(config, "exchange").isBlank() && text(config, "routingKey").isBlank()) missing.add("exchange/routingKey");
    }

    /** 合并版本中的模板快照和画布实例覆盖，保持与执行阶段一致。 */
    private ObjectNode effectiveConfig(JsonNode node, JsonNode snapshot) {
        ObjectNode result = objectMapper.createObjectNode();
        if (snapshot.path("config").isObject()) result.setAll((ObjectNode) snapshot.path("config").deepCopy());
        JsonNode own = node.path("config").isObject() ? node.path("config") : node.path("data").path("config");
        if (own.isObject()) deepMerge(result, own);
        return result;
    }

    /** 递归合并对象覆盖，避免实例只修改嵌套字段时丢失模板默认值。 */
    private void deepMerge(ObjectNode target, JsonNode source) {
        source.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject() && target.path(entry.getKey()).isObject()) {
                deepMerge((ObjectNode) target.path(entry.getKey()), entry.getValue());
            } else target.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }

    /** 返回节点显示名称，缺省时回退稳定节点 ID。 */
    private String nodeName(JsonNode node, String id) {
        String label = node.path("data").path("label").asText().trim();
        return label.isBlank() ? id : label;
    }

    /** 校验字段在配置对象中存在，显式 null 仍视为有效业务值。 */
    private void requirePresent(ObjectNode config, Set<String> missing, String key) {
        if (!config.has(key)) missing.add(key);
    }

    /** 校验字段为非空文本。 */
    private void requireText(ObjectNode config, Set<String> missing, String key) {
        if (text(config, key).isBlank()) missing.add(key);
    }

    /** 校验字段为正数连接或路由标识。 */
    private void requirePositive(ObjectNode config, Set<String> missing, String key) {
        if (!config.hasNonNull(key) || config.path(key).asLong() <= 0) missing.add(key);
    }

    /** 校验字段为 JSON 对象。 */
    private void requireObject(ObjectNode config, Set<String> missing, String key) {
        if (!config.path(key).isObject()) missing.add(key);
    }

    /** 校验字段为达到最少元素数量的 JSON 数组。 */
    private void requireArray(ObjectNode config, Set<String> missing, String key, int minimum) {
        if (!config.path(key).isArray() || config.path(key).size() < minimum) missing.add(key);
    }

    /** 校验结构化条件包含左值、操作符及非一元操作所需右值。 */
    private void requireCondition(ObjectNode config, Set<String> missing, String key) {
        JsonNode condition = config.path(key);
        String operator = condition.path("operator").asText("EQ").toUpperCase(Locale.ROOT);
        if (!condition.isObject() || condition.path("left").asText().trim().isBlank()
            || !UNARY_CONDITION_OPERATORS.contains(operator) && !condition.has("right")) missing.add(key);
    }

    /** 校验一组候选文本中至少存在一个非空值。 */
    private void requireOneText(ObjectNode config, Set<String> missing, String label, String... keys) {
        for (String key : keys) if (!text(config, key).isBlank()) return;
        missing.add(label);
    }

    /** 返回 Redis 白名单命令执行所需的最少参数数量。 */
    private int redisArgumentMinimum(String command) {
        return switch ((command == null ? "GET" : command).toUpperCase(Locale.ROOT)) {
            case "SET", "HGET", "LPUSH", "RPUSH", "PUBLISH" -> 2;
            case "HSET", "LRANGE" -> 3;
            default -> 1;
        };
    }

    /** 安全读取配置文本。 */
    private String text(ObjectNode config, String key) {
        return config.path(key).asText("").trim();
    }
}
