package com.baseai.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/** 统一回填可直接参与执行的节点默认值，避免前后端对“已配置”的理解不一致。 */
final class WorkflowNodeConfigDefaults {
    /** 工具类不允许实例化。 */
    private WorkflowNodeConfigDefaults() { }

    /** 合并模板快照与节点实例覆盖，返回不共享输入节点的隔离副本。 */
    static ObjectNode merge(ObjectMapper objectMapper, JsonNode node, JsonNode snapshot) {
        ObjectNode result = objectMapper.createObjectNode();
        if (snapshot != null && snapshot.path("config").isObject()) {
            result.setAll((ObjectNode) snapshot.path("config").deepCopy());
        }
        JsonNode own = node == null ? objectMapper.nullNode()
            : node.path("config").isObject() ? node.path("config") : node.path("data").path("config");
        if (own.isObject()) deepMerge(result, own);
        return result;
    }

    /** 递归合并对象字段，保留未被覆盖的嵌套配置。 */
    static void deepMerge(ObjectNode target, JsonNode source) {
        source.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject() && target.path(entry.getKey()).isObject()) {
                deepMerge((ObjectNode) target.path(entry.getKey()), entry.getValue());
            } else {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
    }

    /** 返回原配置与有效默认值合并后的隔离副本，空占位字段不会在此回填。 */
    static ObjectNode withDefaults(ObjectMapper objectMapper, String rawType, JsonNode source) {
        ObjectNode config = objectMapper.createObjectNode();
        if (source instanceof ObjectNode object) config.setAll((ObjectNode) object.deepCopy());
        String type = rawType == null ? "" : rawType.toUpperCase(Locale.ROOT);
        switch (type) {
            case "LLM", "AGENT", "QUESTION_CLASSIFIER", "PARAMETER_EXTRACTOR", "RAG" -> {
                putIfAbsent(config, "featureCode", "DEFAULT"); putIfAbsent(config, "modelType", "text_model");
                if ("RAG".equals(type)) { putIfAbsent(config, "topK", 5); putIfAbsent(config, "scoreThreshold", 0); }
            }
            case "EMBEDDING" -> { putIfAbsent(config, "featureCode", "DEFAULT"); putIfAbsent(config, "modelType", "embedding_model"); }
            case "KNOWLEDGE_RETRIEVAL" -> { putIfAbsent(config, "topK", 5); putIfAbsent(config, "scoreThreshold", 0); }
            case "HTTP" -> putIfAbsent(config, "method", "GET");
            case "ITERATION" -> putIfAbsent(config, "collection", "{{input.items}}");
            case "MERGE" -> { putIfAbsent(config, "mode", "ARRAY"); putIfAbsentArray(config, "values"); }
            case "WAIT" -> { putIfAbsent(config, "seconds", 1); putIfAbsent(config, "milliseconds", 1000); }
            case "SET_VARIABLE", "TRANSFORM" -> putIfAbsentObject(config, "output");
            case "SWITCH" -> putIfAbsent(config, "defaultBranch", "default");
            case "FILTER" -> putIfAbsentArray(config, "collection");
            case "SORT" -> { putIfAbsentArray(config, "collection"); putIfAbsent(config, "direction", "ASC"); }
            case "AGGREGATE" -> { putIfAbsentArray(config, "collection"); putIfAbsent(config, "operation", "COUNT"); }
            case "CSV" -> putIfAbsent(config, "operation", "PARSE");
            case "KAFKA_PUBLISH", "RABBITMQ_PUBLISH" -> { if (!config.has("value")) config.putNull("value"); }
            case "TAVILY_TOOL" -> {
                putIfAbsent(config, "searchDepth", "basic"); putIfAbsent(config, "maxResults", 5);
                putIfAbsent(config, "extractDepth", "basic"); putIfAbsent(config, "format", "markdown");
            }
            default -> { }
        }
        return config;
    }

    /** 仅在字段完全缺失时写入文本或数字默认值。 */
    private static void putIfAbsent(ObjectNode config, String key, String value) { if (!config.has(key)) config.put(key, value); }

    /** 仅在字段完全缺失时写入数字默认值。 */
    private static void putIfAbsent(ObjectNode config, String key, int value) { if (!config.has(key)) config.put(key, value); }

    /** 仅在字段完全缺失时写入空数组默认值。 */
    private static void putIfAbsentArray(ObjectNode config, String key) { if (!config.has(key)) config.putArray(key); }

    /** 仅在字段完全缺失时写入空对象默认值。 */
    private static void putIfAbsentObject(ObjectNode config, String key) { if (!config.has(key)) config.putObject(key); }
}
