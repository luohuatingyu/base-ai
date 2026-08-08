package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.AiChatClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 使用平台既有模型路由实现问题分类和结构化参数提取。 */
@Component
public class WorkflowAiNodeExecutor implements WorkflowNodeExecutor {
    private static final Set<String> TYPES = Set.of("QUESTION_CLASSIFIER", "PARAMETER_EXTRACTOR");
    private final ObjectMapper objectMapper;
    private final WorkflowExpressionService expressions;
    private final AiChatClient aiChatClient;

    /** 注入 JSON、表达式和统一模型客户端。 */
    public WorkflowAiNodeExecutor(ObjectMapper objectMapper, WorkflowExpressionService expressions, AiChatClient aiChatClient) {
        this.objectMapper = objectMapper;
        this.expressions = expressions;
        this.aiChatClient = aiChatClient;
    }

    /** 返回 AI 执行器支持的节点集合。 */
    @Override
    public Set<String> types() { return TYPES; }

    /** 执行分类或参数提取并验证模型结构化结果。 */
    @Override
    public Result execute(Request request) {
        JsonNode resolved = expressions.resolve(request.config(), request.context());
        WorkflowNodeConfigValidator.validateResolved(request.type(), resolved);
        return "QUESTION_CLASSIFIER".equals(request.type()) ? classify(resolved) : Result.output(extract(resolved));
    }

    /** 让模型从管理员定义的有限分类中选择唯一结果。 */
    private Result classify(JsonNode config) {
        Set<String> categories = new LinkedHashSet<>();
        StringBuilder choices = new StringBuilder();
        for (JsonNode category : config.path("categories")) {
            String name = category.path("name").asText("").trim();
            if (name.isBlank() || !categories.add(name)) throw new BusinessException("workflow.classifierConfigInvalid");
            choices.append("- ").append(name).append(": ").append(category.path("description").asText("")).append('\n');
        }
        if (categories.size() < 2) throw new BusinessException("workflow.classifierConfigInvalid");
        String system = "Classify the user input into exactly one allowed category. Return only JSON: {\"category\":\"name\"}.\n" + choices;
        JsonNode parsed = chatJson(config, system, config.path("input").asText(""));
        String category = parsed.path("category").asText("");
        if (!categories.contains(category)) throw new BusinessException("workflow.classifierOutputInvalid");
        ObjectNode output = objectMapper.createObjectNode().put("category", category);
        return Result.branch(output, category);
    }

    /** 根据 JSON Schema 从文本中提取结构化参数。 */
    private JsonNode extract(JsonNode config) {
        JsonNode schema = config.path("schema");
        if (!schema.isObject()) throw new BusinessException("workflow.extractorConfigInvalid");
        String system = "Extract parameters from the user input. Return only one JSON object matching this JSON Schema: " + schema;
        JsonNode parsed = chatJson(config, system, config.path("input").asText(""));
        if (!parsed.isObject()) throw new BusinessException("workflow.extractorOutputInvalid");
        schema.path("required").forEach(name -> {
            if (!parsed.has(name.asText()) || parsed.path(name.asText()).isNull()) {
                throw new BusinessException("workflow.extractorOutputInvalid");
            }
        });
        return parsed;
    }

    /** 调用模型并从纯 JSON 或 Markdown 代码块中读取结构化结果。 */
    private JsonNode chatJson(JsonNode config, String system, String input) {
        List<AiChatClient.Message> messages = new ArrayList<>();
        messages.add(new AiChatClient.Message("system", system));
        messages.add(new AiChatClient.Message("user", input));
        Long modelId = config.hasNonNull("modelId") ? config.path("modelId").asLong() : null;
        String modelType = config.hasNonNull("modelType") ? config.path("modelType").asText() : modelId == null ? "text_model" : "";
        AiChatClient.ChatResult result = aiChatClient.chat(config.path("featureCode").asText("DEFAULT"),
            modelType, messages, config.path("temperature").asDouble(0),
            config.has("enableThinking") ? config.path("enableThinking").asBoolean() : null,
            config.path("thinkingLevel").asText(null), modelId);
        String content = result.content() == null ? "" : result.content().trim();
        if (content.startsWith("```")) {
            int firstLine = content.indexOf('\n'); int lastFence = content.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) content = content.substring(firstLine + 1, lastFence).trim();
        }
        try { return objectMapper.readTree(content); }
        catch (Exception exception) { throw new BusinessException("workflow.aiStructuredOutputInvalid"); }
    }
}
