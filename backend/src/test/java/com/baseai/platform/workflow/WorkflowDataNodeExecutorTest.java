package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.document.DocumentParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowDataNodeExecutorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowDataNodeExecutor executor;
    private ObjectNode context;

    /** 创建包含数组和状态字段的通用上下文。 */
    @BeforeEach
    void setUp() throws Exception {
        WorkflowExpressionService expressions = new WorkflowExpressionService(objectMapper);
        PlatformProperties properties = new PlatformProperties(); properties.getWorkflow().setMaxPayloadBytes(32);
        DocumentParser documentParser = mock(DocumentParser.class);
        when(documentParser.parse(any(byte[].class), anyString(), anyInt())).thenAnswer(invocation ->
            new DocumentParser.Result(new String(invocation.getArgument(0), java.nio.charset.StandardCharsets.UTF_8),
                java.util.Map.of("Content-Type", "text/plain")));
        executor = new WorkflowDataNodeExecutor(objectMapper, expressions, properties, documentParser);
        context = (ObjectNode) objectMapper.readTree("""
            {"input":{"status":"PAID","items":[{"score":3},{"score":1},{"score":2}]},"nodes":{},"loop":{}}
            """);
    }

    /** 多路分支必须命中首个条件并返回同名输出端口。 */
    @Test
    void switchSelectsFirstMatchingBranch() throws Exception {
        JsonNode config = objectMapper.readTree("""
            {"cases":[
              {"branch":"paid","condition":{"left":"{{input.status}}","operator":"EQ","right":"PAID"}},
              {"branch":"other","condition":{"left":"{{input.status}}","operator":"EXISTS","right":null}}
            ],"defaultBranch":"default"}
            """);
        WorkflowNodeExecutor.Result result = execute("SWITCH", config);
        assertEquals("paid", result.branch());
        assertTrue(result.output().path("matched").asBoolean());
    }

    /** 数组过滤、排序和聚合覆盖 item 上下文及数值边界。 */
    @Test
    void filtersSortsAndAggregatesArrays() throws Exception {
        JsonNode filtered = execute("FILTER", objectMapper.readTree("""
            {"collection":"{{input.items}}","condition":{"left":"{{item.score}}","operator":"GTE","right":2}}
            """)).output();
        assertEquals(2, filtered.size());
        JsonNode sorted = execute("SORT", objectMapper.readTree("""
            {"collection":"{{input.items}}","path":"score","direction":"ASC"}
            """)).output();
        assertEquals(1, sorted.get(0).path("score").asInt());
        JsonNode aggregate = execute("AGGREGATE", objectMapper.readTree("""
            {"collection":"{{input.items}}","operation":"SUM","path":"score"}
            """)).output();
        assertEquals(6, aggregate.path("value").asInt());
    }

    /** CSV 序列化和解析必须保留逗号与引号字段。 */
    @Test
    void roundTripsCsvEscaping() throws Exception {
        JsonNode serialized = execute("CSV", objectMapper.readTree("""
            {"operation":"STRINGIFY","value":[{"name":"Ada, A.","quote":"\\\"ok\\\""}]}
            """)).output();
        ObjectNode parse = objectMapper.createObjectNode().put("operation", "PARSE").put("value", serialized.path("text").asText());
        JsonNode parsed = execute("CSV", parse).output();
        assertEquals("Ada, A.", parsed.get(0).path("name").asText());
        assertEquals("\"ok\"", parsed.get(0).path("quote").asText());
    }

    /** JSON Schema 校验失败时必须阻止继续执行。 */
    @Test
    void rejectsInvalidJsonSchemaValue() throws Exception {
        JsonNode config = objectMapper.readTree("""
            {"value":{"name":"Ada"},"schema":{"type":"object","required":["age"],
              "properties":{"age":{"type":"integer"}}},"failOnError":true}
            """);
        assertThrows(BusinessException.class, () -> execute("JSON_VALIDATE", config));
    }

    /** 文档提取器必须支持不依赖文件系统的文本内容。 */
    @Test
    void extractsPlainTextDocument() throws Exception {
        JsonNode output = execute("DOCUMENT_EXTRACTOR", objectMapper.readTree("""
            {"inputMode":"TEXT","content":"hello workflow","fileName":"note.txt"}
            """)).output();
        assertTrue(output.path("text").asText().contains("hello workflow"));
    }

    /** 文档必须在 Tika 解析前执行原始字节限制，避免压缩或大正文耗尽内存。 */
    @Test
    void rejectsOversizedDocumentBeforeParsing() throws Exception {
        JsonNode config = objectMapper.createObjectNode().put("inputMode", "TEXT").put("content", "x".repeat(33));
        assertEquals("workflow.payloadTooLarge", assertThrows(BusinessException.class,
            () -> execute("DOCUMENT_EXTRACTOR", config)).getMessageKey());
    }

    /** 构造统一节点请求。 */
    private WorkflowNodeExecutor.Result execute(String type, JsonNode config) {
        return executor.execute(new WorkflowNodeExecutor.Request("run", "node", type, (ObjectNode) config, context, 1L));
    }
}
