package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
        assertDoesNotThrow(() -> validator.validateForPublish(graph("DOCUMENT_EXTRACTOR", "{\"content\":\"plain text\"}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("S3_OBJECT", "{\"connectionId\":1,\"operation\":\"LIST\"}"), objectMapper.createObjectNode()));
        assertDoesNotThrow(() -> validator.validateForPublish(graph("RABBITMQ_PUBLISH", "{\"connectionId\":1,\"routingKey\":\"orders\",\"value\":null}"), objectMapper.createObjectNode()));

        BusinessException document = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("DOCUMENT_EXTRACTOR", "{}"), objectMapper.createObjectNode()));
        BusinessException s3 = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("S3_OBJECT", "{\"connectionId\":1,\"operation\":\"GET\"}"), objectMapper.createObjectNode()));
        BusinessException redis = assertThrows(BusinessException.class,
            () -> validator.validateForPublish(graph("REDIS_COMMAND", "{\"connectionId\":1,\"command\":\"HSET\",\"arguments\":[\"key\",\"field\"]}"), objectMapper.createObjectNode()));

        assertTrue(document.getMessage().contains("contentOrBase64"));
        assertTrue(s3.getMessage().contains("key"));
        assertTrue(redis.getMessage().contains("arguments"));
    }

    /** 具有明确运行默认值或无需配置的节点不应被误判为缺少必填项。 */
    @Test
    void allowsNodesWithRuntimeDefaults() throws Exception {
        JsonNode graph = json("""
            {"nodes":[
              {"id":"start","type":"START","data":{"config":{}}},
              {"id":"llm","type":"LLM","data":{"config":{}}},
              {"id":"wait","type":"WAIT","data":{"config":{}}},
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
}
