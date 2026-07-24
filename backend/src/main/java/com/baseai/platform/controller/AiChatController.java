package com.baseai.platform.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.baseai.platform.trace.TraceType;
import com.baseai.platform.security.RequiredPermission;
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
                return new RouteOption(r.id(), r.featureCode(), r.name());
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
                    m -> new ModelOption(m.getId(), m.getName(), m.getModelName(), m.getModelType()),
                    java.util.stream.Collectors.toList())));

        List<ProviderModels> result = providers.values().stream()
            .filter(p -> modelsByProvider.containsKey(p.id()))
            .map(p -> new ProviderModels(p.id(), p.code(), p.name(), modelsByProvider.get(p.id())))
            .sorted(java.util.Comparator.comparing(ProviderModels::id))
            .toList();

        log.info("event=get_chat_providers count={}", result.size());
        return result;
    }

    /** 建立任务上下文并代理一次通用模型对话。 */
    @PostMapping
    @TraceType(value = "AI 对话", triggerEntry = "MANUAL", captureRequest = false)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("event=ai_chat_started message_count={}", request.messages() == null ? 0 : request.messages().size());
        AiChatClient.ChatResult result = client.chat(request.featureCode(), request.modelType(), request.messages(),
            request.temperature(), request.enableThinking(), request.thinkingLevel(), request.modelId());
        log.info("event=ai_chat_succeeded model={} total_tokens={}", result.model(), result.totalTokens());
        return new ChatResponse(com.baseai.platform.trace.TraceContextHolder.currentTraceId().orElse(""), result.content(),
            result.model(), result.inputTokens(), result.outputTokens(), result.totalTokens());
    }

    /** 路由选项，用于前端下拉列表。 */
    public record RouteOption(Long id, String featureCode, String name) {}
    /** 供应商及其可用模型，用于对话页“单模型”模式级联选择。 */
    public record ProviderModels(Long id, String code, String name, List<ModelOption> models) {}
    /** 模型选项，用于前端下拉列表。 */
    public record ModelOption(Long id, String name, String modelName, String modelType) {}
    /** AI 对话请求参数，字段名称与前端接口协议保持一致。 */
    public record ChatRequest(@JsonProperty("model_type") String modelType, String featureCode,
                              List<AiChatClient.Message> messages, Double temperature,
                              Boolean enableThinking, String thinkingLevel, Long modelId) {}
    /** AI 对话响应，包含追踪标识、模型结果和 Token 统计。 */
    public record ChatResponse(String traceId, String content, String model, int inputTokens, int outputTokens, int totalTokens) {}
}
