package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowNodeConfigValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowNodeConfigValidator validator = new WorkflowNodeConfigValidator(objectMapper);

    /** 模板快照和实例覆盖合并后满足必填配置时应允许发布。 */
    @Test
    void acceptsRequiredFieldsMergedFromTemplateAndInstance() throws Exception {
        JsonNode graph = json("""
            {"nodes":[{"id":"sql","type":"SQL_QUERY","data":{"label":"订单查询","config":{"query":"SELECT * FROM orders"}}}]}
            """);
        JsonNode snapshots = json("""
            {"sql":{"config":{"connectionId":12,"timeoutSeconds":30}}}
            """);

        assertDoesNotThrow(() -> validator.validateForPublish(graph, snapshots));
    }

    /** 每一种平台原生节点都必须由必填规则目录显式覆盖。 */
    @Test
    void coversEveryNativeNodeType() {
        assertEquals(WorkflowNodeTypes.ALL, WorkflowNodeConfigValidator.supportedTypes());
    }

    /** 全部原生节点提供最小有效配置时都应通过统一校验。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("validNativeNodeConfigurations")
    void acceptsMinimumValidConfigurationForEveryNode(String type, String config) throws Exception {
        assertDoesNotThrow(() -> validator.validateForPublish(graph(type, config), objectMapper.createObjectNode()));
    }

    /** 发布校验应汇总主画布和嵌套子画布中的缺失字段。 */
    @Test
    void reportsRootAndNestedMissingRequirementsTogether() throws Exception {
        JsonNode graph = json("""
            {"nodes":[
              {"id":"http","type":"HTTP","data":{"label":"调用接口","config":{}}},
              {"id":"loop","type":"ITERATION","data":{"label":"遍历订单","config":{"collection":"{{input.items}}","bodyGraph":{"nodes":[
                {"id":"nested-sql","type":"SQL_QUERY","data":{"label":"查询明细","config":{"connectionId":8}}}
              ],"edges":[]}}}}
            ]}
            """);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph, objectMapper.createObjectNode()));

        assertEquals("workflow.nodeConfigRequired", exception.getMessageKey());
        assertTrue(exception.getMessage().contains("调用接口（HTTP）：url"));
        assertTrue(exception.getMessage().contains("查询明细（SQL_QUERY）：query"));
    }

    /** 组合条件和操作条件满足时允许发布，缺失时返回稳定字段标识。 */
    @Test
    void validatesConditionalRequirements() throws Exception {
        assertDoesNotThrow(() -> validator.validateForPublish(graph("DOCUMENT_EXTRACTOR", "{\"inputMode\":\"TEXT\",\"content\":\"plain text\"}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("S3_OBJECT", "{\"connectionId\":1,\"operation\":\"LIST\"}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("RABBITMQ_PUBLISH", "{\"connectionId\":1,\"destinationMode\":\"DEFAULT_EXCHANGE\",\"routingKey\":\"orders\",\"value\":null}"), objectMapper.createObjectNode()));

        BusinessException document = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("DOCUMENT_EXTRACTOR", "{}"), objectMapper.createObjectNode()));
        BusinessException s3 = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("S3_OBJECT", "{\"connectionId\":1,\"operation\":\"GET\"}"), objectMapper.createObjectNode()));
        BusinessException redis = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("REDIS_COMMAND", "{\"connectionId\":1,\"command\":\"HSET\",\"arguments\":[\"key\",\"field\"]}"), objectMapper.createObjectNode()));

        assertTrue(document.getMessage().contains("inputMode"));
        assertTrue(s3.getMessage().contains("key"));
        assertTrue(redis.getMessage().contains("arguments"));
    }

    /** AI 节点必须明确选择模型来源，并按所选方案校验模型字段。 */
    @Test
    void validatesAiModelModesAndPrompts() throws Exception {
        assertDoesNotThrow(() -> validator.validateForPublish(graph("LLM",
            "{\"modelMode\":\"ROUTE\",\"featureCode\":\"CHAT\",\"modelType\":\"text_model\",\"prompt\":\"hello\"}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("LLM",
            "{\"modelMode\":\"DIRECT\",\"modelId\":7,\"prompt\":\"hello\"}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("LLM",
            "{\"modelMode\":\"ROUTE\",\"prompt\":\"hello\"}"), objectMapper.createObjectNode()));

        BusinessException missingMode = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("LLM", "{\"prompt\":\"hello\"}"), objectMapper.createObjectNode()));
        BusinessException blankPrompt = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("LLM", "{\"modelMode\":\"DIRECT\",\"modelId\":7,\"prompt\":\" \"}"), objectMapper.createObjectNode()));

        assertTrue(missingMode.getMessage().contains("modelMode"));
        assertTrue(blankPrompt.getMessage().contains("prompt"));
    }

    /** RAG 节点必须同时提供知识库、检索参数和文本模型来源。 */
    @Test
    void validatesRagKnowledgeBaseAndSearchParameters() throws Exception {
        assertDoesNotThrow(() -> validator.validateForPublish(graph("RAG",
            "{\"knowledgeBaseId\":3,\"query\":\"{{input.query}}\",\"topK\":5,\"scoreThreshold\":0.2,\"modelMode\":\"DIRECT\",\"modelId\":9}"), objectMapper.createObjectNode()));

        BusinessException missingBase = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("RAG", "{\"query\":\"q\",\"topK\":5,\"scoreThreshold\":0,\"modelMode\":\"DIRECT\",\"modelId\":9}"), objectMapper.createObjectNode()));
        BusinessException invalidRange = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("RAG", "{\"knowledgeBaseId\":3,\"query\":\"q\",\"topK\":51,\"scoreThreshold\":2,\"modelMode\":\"DIRECT\",\"modelId\":9}"), objectMapper.createObjectNode()));

        assertTrue(missingBase.getMessage().contains("knowledgeBaseId"));
        assertTrue(invalidRange.getMessage().contains("topK"));
        assertTrue(invalidRange.getMessage().contains("scoreThreshold"));
    }

    /** 知识库检索与入库必须校验所有权目标、检索边界及文档输入方案。 */
    @Test
    void validatesKnowledgeRetrievalAndUpsertParameters() throws Exception {
        assertDoesNotThrow(() -> validator.validateForPublish(graph("KNOWLEDGE_RETRIEVAL",
            "{\"knowledgeBaseId\":3,\"query\":\"q\",\"topK\":50,\"scoreThreshold\":1}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("KNOWLEDGE_UPSERT",
            "{\"knowledgeBaseId\":3,\"inputMode\":\"TEXT\",\"content\":\"document\",\"fileName\":\"note.txt\",\"contentType\":\"text/plain\"}"), objectMapper.createObjectNode()));

        BusinessException retrieval = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("KNOWLEDGE_RETRIEVAL",
                "{\"knowledgeBaseId\":3,\"query\":\"q\",\"topK\":0,\"scoreThreshold\":-1}"), objectMapper.createObjectNode()));
        BusinessException upsert = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("KNOWLEDGE_UPSERT",
                "{\"knowledgeBaseId\":3,\"inputMode\":\"BASE64\",\"base64\":\"not-base64\",\"fileName\":\"\",\"contentType\":\"\"}"), objectMapper.createObjectNode()));

        assertTrue(retrieval.getMessage().contains("topK"));
        assertTrue(retrieval.getMessage().contains("scoreThreshold"));
        assertTrue(upsert.getMessage().contains("base64"));
        assertTrue(upsert.getMessage().contains("fileName"));
        assertTrue(upsert.getMessage().contains("contentType"));
    }

    /** 有效默认值应参与必填校验，空占位默认值仍然必须由用户补充。 */
    @Test
    void acceptsValidDefaultsButRejectsEmptyPlaceholders() throws Exception {
        assertDoesNotThrow(() -> validator.validateForPublish(graph("HTTP", "{\"url\":\"https://example.test\"}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("MERGE", "{}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("SET_VARIABLE", "{}"), objectMapper.createObjectNode()));

        BusinessException http = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("HTTP", "{}"), objectMapper.createObjectNode()));
        BusinessException llm = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("LLM", "{\"modelMode\":\"ROUTE\"}"), objectMapper.createObjectNode()));

        assertTrue(http.getMessage().contains("url"));
        assertTrue(llm.getMessage().contains("prompt"));
    }

    /** 邮件发送仅要求路由和主题，正文允许缺失或为空。 */
    @Test
    void requiresEmailSubjectButAllowsEmptyBody() throws Exception {
        assertDoesNotThrow(() -> validator.validateForPublish(graph("EMAIL_SEND",
            "{\"routeId\":5,\"subject\":\"Order ready\"}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("EMAIL_SEND",
            "{\"routeId\":5,\"subject\":\"Order ready\",\"body\":\"\"}"), objectMapper.createObjectNode()));
        BusinessException exception = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("EMAIL_SEND", "{\"routeId\":5,\"subject\":\" \"}"), objectMapper.createObjectNode()));
        assertTrue(exception.getMessage().contains("subject"));
    }

    /** 具有明确运行默认值或无需配置的节点不应被误判为缺少必填项。 */
    @Test
    void allowsNodesWithRuntimeDefaults() throws Exception {
        JsonNode graph = json("""
            {"nodes":[
              {"id":"start","type":"START","data":{"config":{}}},
              {"id":"end","type":"END","data":{"config":{}}}
            ]}
            """);

        assertDoesNotThrow(() -> validator.validateForPublish(graph, objectMapper.createObjectNode()));
    }

    /** 创建包含单个待校验节点的最小配置图。 */
    private JsonNode graph(String type, String config) throws Exception {
        return json("{\"nodes\":[{\"id\":\"node\",\"type\":\"" + type + "\",\"data\":{\"config\":" + config + "}}]}");
    }

    /** 解析测试 JSON。 */
    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    /** 返回覆盖全部节点类型及所有配置方案的最小有效配置。 */
    private static Stream<Arguments> validNativeNodeConfigurations() {
        Map<String, String> configurations = Map.ofEntries(
            Map.entry("START", "{}"), Map.entry("END", "{}"),
            Map.entry("LLM", "{\"modelMode\":\"DIRECT\",\"modelId\":1,\"prompt\":\"hello\"}"),
            Map.entry("HTTP", "{\"method\":\"GET\",\"url\":\"https://example.test\"}"),
            Map.entry("AGENT", "{\"modelMode\":\"DIRECT\",\"modelId\":1,\"prompt\":\"work\",\"tools\":[{\"name\":\"lookup\",\"toolType\":\"HTTP\",\"config\":{\"url\":\"https://example.test\"}}]}"),
            Map.entry("RAG", "{\"knowledgeBaseId\":1,\"query\":\"hello\",\"topK\":5,\"scoreThreshold\":0,\"modelMode\":\"DIRECT\",\"modelId\":1}"),
            Map.entry("KNOWLEDGE_RETRIEVAL", "{\"knowledgeBaseId\":1,\"query\":\"hello\",\"topK\":5,\"scoreThreshold\":0}"),
            Map.entry("KNOWLEDGE_UPSERT", "{\"knowledgeBaseId\":1,\"inputMode\":\"TEXT\",\"content\":\"hello\",\"fileName\":\"note.txt\",\"contentType\":\"text/plain\"}"),
            Map.entry("CONDITION", "{\"condition\":{\"left\":\"{{input.ok}}\",\"operator\":\"EQ\",\"right\":true}}"),
            Map.entry("ITERATION", "{\"collection\":\"{{input.items}}\",\"bodyGraph\":{}}"),
            Map.entry("LOOP", "{\"condition\":{\"left\":\"{{input.ok}}\",\"operator\":\"EXISTS\"},\"bodyGraph\":{}}"),
            Map.entry("SWITCH", "{\"cases\":[{\"branch\":\"yes\",\"condition\":{\"left\":\"{{input.ok}}\",\"operator\":\"EQ\",\"right\":true}}],\"defaultBranch\":\"default\"}"),
            Map.entry("MERGE", "{\"mode\":\"ARRAY\",\"values\":[]}"),
            Map.entry("SUB_WORKFLOW", "{\"workflowCode\":\"CHILD\"}"),
            Map.entry("WAIT", "{\"durationMode\":\"SECONDS\",\"seconds\":1}"),
            Map.entry("SET_VARIABLE", "{\"output\":{}}"), Map.entry("TEMPLATE", "{\"template\":\"hello\"}"),
            Map.entry("JSON_PARSE", "{\"value\":null}"),
            Map.entry("JSON_VALIDATE", "{\"value\":null,\"schema\":{}}"),
            Map.entry("TRANSFORM", "{\"output\":{}}"),
            Map.entry("FILTER", "{\"collection\":[],\"condition\":{\"left\":\"{{item}}\",\"operator\":\"EXISTS\"}}"),
            Map.entry("SORT", "{\"collection\":[],\"direction\":\"ASC\"}"),
            Map.entry("AGGREGATE", "{\"collection\":[],\"operation\":\"COUNT\"}"),
            Map.entry("CSV", "{\"operation\":\"PARSE\",\"value\":\"name\\nAda\"}"),
            Map.entry("QUESTION_CLASSIFIER", "{\"modelMode\":\"DIRECT\",\"modelId\":1,\"input\":\"hello\",\"categories\":[{\"name\":\"A\"},{\"name\":\"B\"}]}"),
            Map.entry("PARAMETER_EXTRACTOR", "{\"modelMode\":\"DIRECT\",\"modelId\":1,\"input\":\"hello\",\"schema\":{}}"),
            Map.entry("STRUCTURED_OUTPUT", "{\"value\":{},\"schema\":{}}"),
            Map.entry("DOCUMENT_EXTRACTOR", "{\"inputMode\":\"TEXT\",\"content\":\"hello\"}"),
            Map.entry("WEBHOOK_TRIGGER", "{\"connectionId\":1}"),
            Map.entry("SCHEDULE_TRIGGER", "{\"cron\":\"0 * * * * *\"}"),
            Map.entry("EMAIL_SEND", "{\"routeId\":1,\"subject\":\"Notice\"}"),
            Map.entry("IM_NOTIFY", "{\"connectionId\":1}"),
            Map.entry("SQL_QUERY", "{\"connectionId\":1,\"query\":\"SELECT 1\"}"),
            Map.entry("REDIS_COMMAND", "{\"connectionId\":1,\"command\":\"GET\",\"arguments\":[\"key\"]}"),
            Map.entry("S3_OBJECT", "{\"connectionId\":1,\"operation\":\"LIST\"}"),
            Map.entry("KAFKA_PUBLISH", "{\"connectionId\":1,\"topic\":\"events\",\"value\":null}"),
            Map.entry("KAFKA_TRIGGER", "{\"connectionId\":1,\"topic\":\"events\"}"),
            Map.entry("RABBITMQ_PUBLISH", "{\"connectionId\":1,\"destinationMode\":\"DEFAULT_EXCHANGE\",\"routingKey\":\"events\",\"value\":null}"),
            Map.entry("RABBITMQ_TRIGGER", "{\"connectionId\":1,\"queue\":\"events\"}"),
            Map.entry("TAVILY_TOOL", "{\"connectionId\":1,\"operation\":\"SEARCH\",\"query\":\"hello\",\"maxResults\":20}")
        );
        return configurations.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }
}
