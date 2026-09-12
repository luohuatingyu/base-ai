package com.baseai.platform.chat;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.trace.TraceContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** 登录用户会话管理及 SSE 输出，同一请求线程保留鉴权和追踪上下文。 */
@RestController
@RequestMapping("/api/ai/conversations")
@RequiredPermission("ai:chat:invoke")
public class ChatConversationController {
    private final ChatConversationService service;
    private final ChatStreamClient client;
    private final ObjectMapper mapper;
    private final Semaphore slots = new Semaphore(16);

    /** 注入会话存储及真实流式客户端。 */
    public ChatConversationController(ChatConversationService service, ChatStreamClient client, ObjectMapper mapper) {
        this.service = service; this.client = client; this.mapper = mapper;
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
    @PostMapping(value = "/{id}/messages/stream", produces = "text/event-stream")
    @com.baseai.platform.trace.TraceType(value = "AI_CHAT_STREAM", captureRequest = false)
    public void send(@PathVariable Long id, @RequestBody ChatConversationService.SendRequest request,
                     HttpServletResponse response) {
        if (!slots.tryAcquire()) throw new BusinessException(503, "chat.busy");
        try {
            var turn = service.begin(AuthContext.require().id(), id, request);
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
                    } else if (!"done".equals(type) && !"heartbeat".equals(type)) throw new IOException("Invalid event");
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
                try { write(response, mapper.valueToTree(Map.of("type", "error", "message", "ai.serviceCallFailed"))); }
                catch (IOException ignored) { }
                throw new StreamFailure(exception);
            }
        } finally { slots.release(); }
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
}
