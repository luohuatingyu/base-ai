package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/** 执行知识库纯检索和工作流动态文档入库节点。 */
@Component
public class WorkflowKnowledgeNodeExecutor implements WorkflowNodeExecutor {
    private static final Set<String> TYPES = Set.of("KNOWLEDGE_RETRIEVAL", "KNOWLEDGE_UPSERT");
    private final ObjectMapper objectMapper;
    private final WorkflowExpressionService expressions;
    private final KnowledgeBaseService knowledgeBaseService;

    /** 注入表达式、JSON 和知识库服务。 */
    public WorkflowKnowledgeNodeExecutor(ObjectMapper objectMapper, WorkflowExpressionService expressions,
                                         KnowledgeBaseService knowledgeBaseService) {
        this.objectMapper = objectMapper;
        this.expressions = expressions;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 返回知识库叶子节点类型。 */
    @Override
    public Set<String> types() { return TYPES; }

    /** 解析配置并在工作流所有者边界内检索或索引文档。 */
    @Override
    public Result execute(Request request) {
        JsonNode config = expressions.resolve(WorkflowNodeConfigDefaults.withDefaults(
            objectMapper, request.type(), request.config()), request.context());
        WorkflowNodeConfigValidator.validateResolved(request.type(), config);
        return Result.output(switch (request.type()) {
            case "KNOWLEDGE_RETRIEVAL" -> retrieve(config, request.workflowOwnerId());
            case "KNOWLEDGE_UPSERT" -> upsert(config, request.workflowOwnerId());
            default -> throw new BusinessException("workflow.nodeTypeInvalid");
        });
    }

    /** 返回不调用生成模型的知识库匹配片段和引用元数据。 */
    private JsonNode retrieve(JsonNode config, Long ownerId) {
        KnowledgeBaseService.Retrieval retrieval = knowledgeBaseService.retrieve(
            config.path("knowledgeBaseId").asLong(), config.path("query").asText(),
            config.path("topK").asInt(5), config.path("scoreThreshold").asDouble(0), ownerId);
        ObjectNode output = objectMapper.createObjectNode()
            .put("knowledgeBaseId", retrieval.knowledgeBaseId())
            .put("knowledgeBaseName", retrieval.knowledgeBaseName())
            .put("count", retrieval.matches().size());
        ArrayNode matches = output.putArray("matches");
        retrieval.matches().forEach(item -> matches.addObject()
            .put("chunkId", item.chunkId()).put("documentId", item.documentId())
            .put("fileName", item.fileName()).put("content", item.content()).put("score", item.score()));
        return output;
    }

    /** 将文本或 Base64 文档交给知识库既有提取、切片和向量索引流程。 */
    private JsonNode upsert(JsonNode config, Long ownerId) {
        String mode = config.path("inputMode").asText().toUpperCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = "BASE64".equals(mode) ? Base64.getDecoder().decode(config.path("base64").asText())
                : config.path("content").asText().getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("workflow.dataInputInvalid");
        }
        KnowledgeBaseService.DocumentView document = knowledgeBaseService.indexDocumentForOwner(
            config.path("knowledgeBaseId").asLong(), config.path("fileName").asText(),
            config.path("contentType").asText(), bytes, ownerId);
        return objectMapper.createObjectNode().put("documentId", document.id()).put("fileName", document.fileName())
            .put("status", document.status()).put("chunkCount", document.chunkCount());
    }
}
