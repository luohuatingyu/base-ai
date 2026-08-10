package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowKnowledgeNodeExecutorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final WorkflowKnowledgeNodeExecutor executor = new WorkflowKnowledgeNodeExecutor(
        objectMapper, new WorkflowExpressionService(objectMapper), knowledgeBaseService);

    /** 纯检索必须沿用工作流所有者边界并完整返回匹配片段。 */
    @Test
    void retrievesKnowledgeForWorkflowOwnerWithoutGeneration() {
        when(knowledgeBaseService.retrieve(9L, "question", 3, 0.2, 7L)).thenReturn(
            new KnowledgeBaseService.Retrieval(9L, "Docs", List.of(
                new KnowledgeBaseService.RetrievedChunk(11L, 12L, "guide.txt", "trusted fact", 0.91))));
        ObjectNode config = objectMapper.createObjectNode().put("knowledgeBaseId", 9)
            .put("query", "question").put("topK", 3).put("scoreThreshold", 0.2);

        WorkflowNodeExecutor.Result result = execute("KNOWLEDGE_RETRIEVAL", config, 7L);

        assertEquals(1, result.output().path("count").asInt());
        assertEquals("trusted fact", result.output().path("matches").get(0).path("content").asText());
        assertEquals(0.91, result.output().path("matches").get(0).path("score").asDouble());
        verify(knowledgeBaseService).retrieve(9L, "question", 3, 0.2, 7L);
    }

    /** 文本入库必须把原始字节和显式所有者交给知识库索引流程。 */
    @Test
    void indexesTextForWorkflowOwner() {
        when(knowledgeBaseService.indexDocumentForOwner(eq(9L), eq("note.txt"), eq("text/plain"), any(byte[].class), eq(7L)))
            .thenReturn(document(21L, "note.txt", 2));
        ObjectNode config = objectMapper.createObjectNode().put("knowledgeBaseId", 9).put("inputMode", "TEXT")
            .put("content", "hello knowledge").put("fileName", "note.txt").put("contentType", "text/plain");

        WorkflowNodeExecutor.Result result = execute("KNOWLEDGE_UPSERT", config, 7L);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(knowledgeBaseService).indexDocumentForOwner(eq(9L), eq("note.txt"), eq("text/plain"), bytes.capture(), eq(7L));
        assertArrayEquals("hello knowledge".getBytes(StandardCharsets.UTF_8), bytes.getValue());
        assertEquals(21L, result.output().path("documentId").asLong());
        assertEquals(2, result.output().path("chunkCount").asInt());
    }

    /** Base64 入库必须先解码，非法内容不得触发知识库副作用。 */
    @Test
    void decodesBase64AndRejectsInvalidContentBeforeIndexing() {
        byte[] content = "document".getBytes(StandardCharsets.UTF_8);
        when(knowledgeBaseService.indexDocumentForOwner(eq(9L), eq("note.txt"), eq("text/plain"), any(byte[].class), eq(7L)))
            .thenReturn(document(22L, "note.txt", 1));
        ObjectNode valid = objectMapper.createObjectNode().put("knowledgeBaseId", 9).put("inputMode", "BASE64")
            .put("base64", Base64.getEncoder().encodeToString(content)).put("fileName", "note.txt").put("contentType", "text/plain");
        execute("KNOWLEDGE_UPSERT", valid, 7L);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(knowledgeBaseService).indexDocumentForOwner(eq(9L), eq("note.txt"), eq("text/plain"), bytes.capture(), eq(7L));
        assertArrayEquals(content, bytes.getValue());

        clearInvocations(knowledgeBaseService);
        ObjectNode invalid = valid.deepCopy().put("base64", "not-base64!");
        assertThrows(BusinessException.class, () -> execute("KNOWLEDGE_UPSERT", invalid, 7L));
        verifyNoInteractions(knowledgeBaseService);
    }

    /** 构造一次叶子节点执行请求。 */
    private WorkflowNodeExecutor.Result execute(String type, ObjectNode config, Long ownerId) {
        return executor.execute(new WorkflowNodeExecutor.Request("run", "node", type, config,
            objectMapper.createObjectNode(), ownerId));
    }

    /** 构造已完成索引的知识库文档视图。 */
    private KnowledgeBaseService.DocumentView document(Long id, String fileName, int chunks) {
        LocalDateTime now = LocalDateTime.now();
        return new KnowledgeBaseService.DocumentView(id, fileName, "text/plain", "READY", chunks, "", now, now);
    }
}
