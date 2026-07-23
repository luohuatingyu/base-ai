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
        return llmManagementService.routes().stream()
            .filter(LlmManagementService.RouteView::enabled)
            .map(r -> new RouteOption(r.id(), r.featureCode(), r.name()))
            .toList();
    }

    /** 建立任务上下文并代理一次通用模型对话。 */
    @PostMapping
    @TraceType(value = "AI 对话", triggerEntry = "MANUAL", captureRequest = false)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("event=ai_chat_started message_count={}", request.messages() == null ? 0 : request.messages().size());
        AiChatClient.ChatResult result = client.chat(request.featureCode(), request.modelType(), request.messages(),
            request.temperature(), request.enableThinking(), request.thinkingLevel());
        log.info("event=ai_chat_succeeded model={} total_tokens={}", result.model(), result.totalTokens());
        return new ChatResponse(com.baseai.platform.trace.TraceContextHolder.currentTraceId().orElse(""), result.content(),
            result.model(), result.inputTokens(), result.outputTokens(), result.totalTokens());
    }

    /** 路由选项，用于前端下拉列表。 */
    public record RouteOption(Long id, String featureCode, String name) {}
    /** AI 对话请求参数，字段名称与前端接口协议保持一致。 */
    public record ChatRequest(@JsonProperty("model_type") String modelType, String featureCode,
                              List<AiChatClient.Message> messages, Double temperature,
                              Boolean enableThinking, String thinkingLevel) {}
    /** AI 对话响应，包含追踪标识、模型结果和 Token 统计。 */
    public record ChatResponse(String traceId, String content, String model, int inputTokens, int outputTokens, int totalTokens) {}
}
