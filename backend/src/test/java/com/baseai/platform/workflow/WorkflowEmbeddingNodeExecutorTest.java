package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.AiChatClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class WorkflowEmbeddingNodeExecutorTest {
    private final ObjectMapper objectMapper=new ObjectMapper();
    private AiChatClient aiChatClient;
    private WorkflowEmbeddingNodeExecutor executor;
    private ObjectNode context;

    /** 创建隔离的表达式上下文和向量客户端替身。 */
    @BeforeEach
    void setUp() throws Exception {
        aiChatClient=mock(AiChatClient.class);
        executor=new WorkflowEmbeddingNodeExecutor(objectMapper,new WorkflowExpressionService(objectMapper),aiChatClient);
        context=(ObjectNode)objectMapper.readTree("{\"input\":{\"text\":\" hello \",\"items\":[\"first\",\"second\"]},\"nodes\":{}}");
    }

    /** 路由模式应解析单文本并返回统一批量向量结构。 */
    @Test
    void embedsSingleTextThroughCapabilityRoute() throws Exception {
        when(aiChatClient.embed("VECTOR",null,List.of("hello"))).thenReturn(
            new AiChatClient.EmbeddingResult(List.of(List.of(1D,0D,0.5D)),"embed-v1"));

        JsonNode output=execute("{\"modelMode\":\"ROUTE\",\"featureCode\":\"VECTOR\",\"input\":\"{{input.text}}\"}");

        assertEquals("embed-v1",output.path("model").asText());assertEquals(1,output.path("count").asInt());
        assertEquals(3,output.path("dimension").asInt());assertEquals(1D,output.path("embeddings").get(0).get(0).asDouble());
    }

    /** 指定模型模式应保持文本数组与向量数组的原始顺序。 */
    @Test
    void embedsTextArrayThroughDirectModel() throws Exception {
        when(aiChatClient.embed("DEFAULT",7L,List.of("first","second"))).thenReturn(
            new AiChatClient.EmbeddingResult(List.of(List.of(1D,0D),List.of(0D,1D)),"embed-v2"));

        JsonNode output=execute("{\"modelMode\":\"DIRECT\",\"modelId\":7,\"input\":\"{{input.items}}\"}");

        assertEquals(2,output.path("count").asInt());assertEquals(2,output.path("embeddings").size());
        verify(aiChatClient).embed("DEFAULT",7L,List.of("first","second"));
    }

    /** 非文本数组必须在外部模型调用前失败。 */
    @Test
    void rejectsInvalidInputBeforeCallingModel() throws Exception {
        assertThrows(BusinessException.class,()->execute("{\"modelMode\":\"DIRECT\",\"modelId\":7,\"input\":[\"ok\",1]}"));
        verifyNoInteractions(aiChatClient);
    }

    /** 执行一次向量化节点请求。 */
    private JsonNode execute(String config) throws Exception {
        return executor.execute(new WorkflowNodeExecutor.Request("run","embedding","EMBEDDING",
            (ObjectNode)objectMapper.readTree(config),context,1L)).output();
    }
}
