package com.baseai.platform.chat;

import com.baseai.platform.aop.RateLimit;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.trace.TraceContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** 登录用户会话管理及 SSE 输出，同一请求线程保留鉴权和追踪上下文。 */
@RestController
@RequestMapping("/api/ai/conversations")
@RequiredPermission("ai:chat:invoke")
public class ChatConversationController {
    private final ChatConversationService service;
    private final ChatStreamClient client;
    private final ObjectMapper mapper;
    private final AiChatClient aiChatClient;
    private final Semaphore slots = new Semaphore(16);

    /** 注入会话存储及真实流式客户端。 */
    @Autowired
    public ChatConversationController(ChatConversationService service, ChatStreamClient client, ObjectMapper mapper,
                                      AiChatClient aiChatClient) {
        this.service = service; this.client = client; this.mapper = mapper;
        this.aiChatClient = aiChatClient;
    }

    /** 兼容不需要 Agent 工具目录的控制器单元测试和轻量装配。 */
    public ChatConversationController(ChatConversationService service, ChatStreamClient client, ObjectMapper mapper) {
        this(service, client, mapper, null);
    }

    /** 创建当前登录用户的会话。 */
    @PostMapping
    public ChatConversationService.Summary create() { return service.create(AuthContext.require().id()); }

    /** 分页返回当前用户的会话摘要。 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page) { return service.list(AuthContext.require().id(), page); }

    /** 读取私有会话详情。 */
    @GetMapping("/{id}")
    public ChatConversationService.Detail detail(@PathVariable Long id) { return service.detail(AuthContext.require().id(), id); }

    /** 删除当前用户的空闲会话。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) { service.delete(AuthContext.require().id(), id); }

    /** 增量输出并在完成前持久化终态，最多允许十六条活动流。 */
    @PostMapping(value = "/{id}/messages/stream")
    @com.baseai.platform.trace.TraceType(value = "AI_CHAT_STREAM", captureRequest = false)
    @RateLimit(name = "ai-chat", limit = 10, period = 60, limitType = RateLimit.LimitType.USER)
    public void send(@PathVariable Long id, @RequestBody ChatConversationService.SendRequest request,
                     HttpServletResponse response) {
        if (!slots.tryAcquire()) throw new BusinessException(503, "chat.busy");
        try {
            var turn = service.begin(AuthContext.require().id(), id, request);
            if (!request.streaming()) {
                sendNonStreaming(turn, response);
                return;
            }
            StringBuilder content = new StringBuilder();
            long[] savedAt = {System.nanoTime()};
            boolean[] completed = {false};
            try {
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-cache, no-transform");
                response.setHeader("X-Accel-Buffering", "no");
                write(response, mapper.valueToTree(Map.of("type", "start", "messageId", turn.messageId(),
                    "traceId", TraceContextHolder.currentTraceId().orElse(""))));
                client.stream(turn, event -> {
                    String type = event.path("type").asText();
                    if ("delta".equals(type)) {
                        if (!event.path("content").isTextual()) throw new IOException("Invalid delta");
                        String delta = event.path("content").asText();
                        int available = 100000 - content.length();
                        content.append(delta, 0, Math.min(available, delta.length()));
                        if (delta.length() > available) throw new IOException("Answer limit");
                    } else if (!Set.of("model", "done", "heartbeat").contains(type)) {
                        throw new IOException("Invalid event");
                    }
                    if ("done".equals(type)) {
                        if (content.isEmpty()) throw new IOException("Empty answer");
                        service.save(turn, content.toString(), "COMPLETED", event);
                        completed[0] = true;
                    } else if (System.nanoTime() - savedAt[0] > 5_000_000_000L) {
                        service.save(turn, content.toString(), "GENERATING", null);
                        savedAt[0] = System.nanoTime();
                    }
                    write(response, event);
                });
            } catch (Exception exception) {
                if (!completed[0]) service.save(turn, content.toString(), "INTERRUPTED", null);
                try { write(response, errorEvent(exception)); }
                catch (IOException ignored) { }
                throw new StreamFailure(exception);
            }
        } finally { slots.release(); }
    }

    /** 执行非流式会话请求，复用同一路由和 Token 监控并一次性返回 JSON。 */
    private void sendNonStreaming(ChatConversationService.Turn turn, HttpServletResponse response) {
        boolean completed = false;
        try {
            if (aiChatClient == null) throw new IllegalStateException("Chat client is unavailable");
            var settings = turn.settings();
            AiChatClient.ChatResult result = aiChatClient.chat(settings.featureCode(), settings.modelType(), turn.context(),
                0D, settings.enableThinking(), settings.thinkingLevel(), settings.modelId(), "CONVERSATION",
                turn.owner(), turn.conversationId());
            if (result == null || result.content() == null || result.content().isBlank())
                throw new BusinessException("ai.emptyResponse");
            JsonNode metadata = mapper.valueToTree(Map.of("model", result.model(),
                "inputTokens", result.inputTokens(), "outputTokens", result.outputTokens(),
                "totalTokens", result.totalTokens()));
            service.save(turn, result.content(), "COMPLETED", metadata);
            completed = true;
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getOutputStream(), new NonStreamResponse(turn.messageId(),
                TraceContextHolder.currentTraceId().orElse(""), result.content(), result.model(),
                result.inputTokens(), result.outputTokens(), result.totalTokens()));
        } catch (Exception exception) {
            if (!completed) service.save(turn, "", "INTERRUPTED", null);
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new StreamFailure(exception);
        }
    }

    /** 仅向客户端公开固定的路由提示，其他异常不携带内部细节或上游响应。 */
    private JsonNode errorEvent(Exception exception) {
        if (exception instanceof BusinessException business
            && Set.of("llm.defaultPoolUnavailable", "llm.selectedPoolUnavailable").contains(business.getMessageKey())) {
            String message = java.util.ResourceBundle.getBundle("messages",
                org.springframework.context.i18n.LocaleContextHolder.getLocale()).getString(business.getMessageKey());
            return mapper.valueToTree(Map.of("type", "error", "code", business.getMessageKey(), "message", message));
        }
        return mapper.valueToTree(Map.of("type", "error", "message", "ai.serviceCallFailed"));
    }

    /** 每个事件立即刷新，避免代理或 Servlet 缓冲整个回答。 */
    private void write(HttpServletResponse response, JsonNode event) throws IOException {
        response.getOutputStream().write(("data: " + mapper.writeValueAsString(event) + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    /** 已提交的 SSE 响应不再由通用异常处理器写入 JSON；追踪切面仍记录失败。 */
    @ExceptionHandler(StreamFailure.class)
    @com.baseai.platform.trace.TraceIgnored
    public void streamFailure(StreamFailure exception, HttpServletResponse response) { }

    /** 区分已进入流式协议后的失败。 */
    public static class StreamFailure extends RuntimeException {
        /** 保留内部异常用于追踪，不返回异常细节。 */
        public StreamFailure(Throwable cause) { super("Chat stream interrupted", cause); }
    }

    /** 非流式会话响应，字段与流式 done 事件保持一致。 */
    public record NonStreamResponse(Long messageId, String traceId, String content, String model,
                                    int inputTokens, int outputTokens, int totalTokens) {}
}
