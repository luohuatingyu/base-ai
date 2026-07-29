package com.baseai.platform.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.baseai.platform.trace.TraceType;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.security.ApiKeyEndpoint;
import com.baseai.platform.security.ApiKeyField;
import com.baseai.platform.security.ApiKeyRisk;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.service.LlmManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 对话接口控制器。
 *
 * <p>负责接收前端对话请求，并委托 {@link AiChatClient} 调用模型服务；
 * 请求会由追踪切面自动纳入任务生命周期管理。</p>
 */
@RestController
@RequestMapping("/api/ai/chat")
@RequiredPermission("ai:chat:invoke")
public class AiChatController {
    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);
    private final AiChatClient client;
    private final LlmManagementService llmManagementService;

    public AiChatController(AiChatClient client, LlmManagementService llmManagementService) {
        this.client = client;
        this.llmManagementService = llmManagementService;
    }

    /** 获取可用的路由列表，供对话页面选择模型池。 */
    @GetMapping("/routes")
    public List<RouteOption> getAvailableRoutes() {
        List<LlmManagementService.RouteView> allRoutes = llmManagementService.routes();
        log.info("event=get_chat_routes total_routes={} enabled_routes={}",
            allRoutes.size(),
            allRoutes.stream().filter(LlmManagementService.RouteView::enabled).count());

        List<RouteOption> result = allRoutes.stream()
            .filter(LlmManagementService.RouteView::enabled)
            .map(r -> {
                log.debug("route: id={} featureCode={} name={} enabled={}",
                    r.id(), r.featureCode(), r.name(), r.enabled());
                return new RouteOption(r.id(), r.featureCode(), r.name(), llmManagementService.routeModelTypes(r.featureCode()));
            })
            .toList();

        log.info("event=get_chat_routes_result count={}", result.size());
        return result;
    }

    /** 获取启用的供应商及其启用的模型，供对话页“单模型”模式级联选择。 */
    @GetMapping("/providers")
    public List<ProviderModels> getAvailableProviders() {
        java.util.Map<Long, LlmManagementService.ProviderView> providers = llmManagementService.providers().stream()
            .filter(LlmManagementService.ProviderView::enabled)
            .collect(java.util.stream.Collectors.toMap(LlmManagementService.ProviderView::id, p -> p));

        java.util.Map<Long, List<ModelOption>> modelsByProvider = llmManagementService.models().stream()
            .filter(m -> Boolean.TRUE.equals(m.getEnabled()) && providers.containsKey(m.getProviderId()))
            .collect(java.util.stream.Collectors.groupingBy(
                com.baseai.platform.domain.LlmModel::getProviderId,
                java.util.stream.Collectors.mapping(
                    m -> new ModelOption(m.getId(), m.getName(), m.getModelName(), m.getSupportedModelTypes()),
                    java.util.stream.Collectors.toList())));

        List<ProviderModels> result = providers.values().stream()
            .filter(p -> modelsByProvider.containsKey(p.id()))
            .map(p -> new ProviderModels(p.id(), p.code(), p.name(), modelsByProvider.get(p.id())))
            .sorted(java.util.Comparator.comparing(ProviderModels::id))
            .toList();

        log.info("event=get_chat_providers count={}", result.size());
        return result;
    }

    /** 获取启用的动态模型类型，供对话页生成类型单选项。 */
    @GetMapping("/model-types")
    public List<LlmManagementService.ModelTypeOption> getAvailableModelTypes() { return llmManagementService.modelTypes(); }

    /** 建立任务上下文并代理一次通用模型对话。 */
    @PostMapping
    @ApiKeyEndpoint(code = "ai.chat.invoke", nameKey = "apiKeys.endpointNames.aiChatInvoke",
        groupKey = "apiKeys.endpointGroups.ai", descriptionKey = "openPlatform.endpointDescriptions.aiChatInvoke",
        risk = ApiKeyRisk.SENSITIVE,
        requestFields = {
            @ApiKeyField(name = "model_type", descriptionKey = "openPlatform.fields.modelType", type = "string",
                defaultValue = "text_model", example = "text_model"),
            @ApiKeyField(name = "featureCode", descriptionKey = "openPlatform.fields.featureCode", type = "string",
                defaultValue = "chat", example = "chat"),
            @ApiKeyField(name = "messages", descriptionKey = "openPlatform.fields.messages", type = "array<object>",
                required = true, example = "[{\"role\":\"user\",\"content\":\"Hello\"}]"),
            @ApiKeyField(name = "messages[].role", descriptionKey = "openPlatform.fields.messageRole", type = "string",
                required = true, example = "user"),
            @ApiKeyField(name = "messages[].content", descriptionKey = "openPlatform.fields.messageContent", type = "any",
                required = true, example = "Hello"),
            @ApiKeyField(name = "temperature", descriptionKey = "openPlatform.fields.temperature", type = "number",
                defaultValue = "0", example = "0.7"),
            @ApiKeyField(name = "enableThinking", descriptionKey = "openPlatform.fields.enableThinking", type = "boolean",
                example = "false"),
            @ApiKeyField(name = "thinkingLevel", descriptionKey = "openPlatform.fields.thinkingLevel", type = "string",
                example = "LOW"),
            @ApiKeyField(name = "modelId", descriptionKey = "openPlatform.fields.modelId", type = "integer",
                example = "1")
        },
        responseFields = {
            @ApiKeyField(name = "success", descriptionKey = "openPlatform.fields.success", type = "boolean", required = true,
                example = "true"),
            @ApiKeyField(name = "code", descriptionKey = "openPlatform.fields.code", type = "integer", required = true,
                example = "200"),
            @ApiKeyField(name = "message", descriptionKey = "openPlatform.fields.message", type = "string", required = true,
                example = "Success"),
            @ApiKeyField(name = "traceId", descriptionKey = "openPlatform.fields.traceId", type = "string", required = true,
                example = "trace-id"),
            @ApiKeyField(name = "data.content", descriptionKey = "openPlatform.fields.content", type = "string", required = true,
                example = "Hello!"),
            @ApiKeyField(name = "data.model", descriptionKey = "openPlatform.fields.model", type = "string", required = true,
                example = "example-model"),
            @ApiKeyField(name = "data.inputTokens", descriptionKey = "openPlatform.fields.inputTokens", type = "integer", required = true,
                example = "8"),
            @ApiKeyField(name = "data.outputTokens", descriptionKey = "openPlatform.fields.outputTokens", type = "integer", required = true,
                example = "4"),
            @ApiKeyField(name = "data.totalTokens", descriptionKey = "openPlatform.fields.totalTokens", type = "integer", required = true,
                example = "12")
        },
        requestExample = "{\n  \"model_type\": \"text_model\",\n  \"featureCode\": \"chat\",\n  \"messages\": [\n    { \"role\": \"user\", \"content\": \"Hello\" }\n  ],\n  \"temperature\": 0.7,\n  \"enableThinking\": false\n}",
        responseExample = "{\n  \"success\": true,\n  \"code\": 200,\n  \"message\": \"Success\",\n  \"traceId\": \"trace-id\",\n  \"data\": {\n    \"content\": \"Hello!\",\n    \"model\": \"example-model\",\n    \"inputTokens\": 8,\n    \"outputTokens\": 4,\n    \"totalTokens\": 12\n  }\n}")
    @TraceType(value = "AI_CHAT", triggerEntry = "MANUAL", captureRequest = false)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("event=ai_chat_started message_count={}", request.messages() == null ? 0 : request.messages().size());
        AiChatClient.ChatResult result = client.chat(request.featureCode(), request.modelType(), request.messages(),
            request.temperature(), request.enableThinking(), request.thinkingLevel(), request.modelId());
        log.info("event=ai_chat_succeeded model={} total_tokens={}", result.model(), result.totalTokens());
        return new ChatResponse(result.content(), result.model(), result.inputTokens(), result.outputTokens(), result.totalTokens());
    }

    /** 路由选项，用于前端下拉列表。 */
    public record RouteOption(Long id, String featureCode, String name, List<String> supportedModelTypes) {}
    /** 供应商及其可用模型，用于对话页“单模型”模式级联选择。 */
    public record ProviderModels(Long id, String code, String name, List<ModelOption> models) {}
    /** 模型选项，用于前端下拉列表。 */
    public record ModelOption(Long id, String name, String modelName, List<String> supportedModelTypes) {}
    /** AI 对话请求参数，字段名称与前端接口协议保持一致。 */
    public record ChatRequest(@JsonProperty("model_type") String modelType, String featureCode,
                              List<AiChatClient.Message> messages, Double temperature,
                              Boolean enableThinking, String thinkingLevel, Long modelId) {}
    /** AI 对话业务响应，仅包含模型结果和 Token 统计。 */
    public record ChatResponse(String content, String model, int inputTokens, int outputTokens, int totalTokens) {}
}
