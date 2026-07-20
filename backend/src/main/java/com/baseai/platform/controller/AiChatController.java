package com.baseai.platform.controller;

import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.service.TaskJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/chat")
@RequiredPermission("ai:chat:invoke")
public class AiChatController {
    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);
    private final AiChatClient client;
    private final TaskJobService taskJobService;

    public AiChatController(AiChatClient client, TaskJobService taskJobService) {
        this.client = client;
        this.taskJobService = taskJobService;
    }

    /** 建立任务上下文并代理一次通用模型对话。 */
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String jobId = taskJobService.start(AuthContext.require().id(), "AI_CHAT", "/api/ai/chat");
        MDC.put("jobId", jobId);
        try {
            log.info("event=ai_chat_started message_count={}", request.messages() == null ? 0 : request.messages().size());
            AiChatClient.ChatResult result = client.chat(request.messages(), request.temperature());
            taskJobService.success(jobId);
            log.info("event=ai_chat_succeeded model={} total_tokens={}", result.model(), result.totalTokens());
            return new ChatResponse(jobId, result.content(), result.model(), result.inputTokens(), result.outputTokens(), result.totalTokens());
        } catch (RuntimeException exception) {
            taskJobService.failed(jobId, exception.getMessage());
            log.error("event=ai_chat_failed", exception);
            throw exception;
        } finally {
            MDC.remove("jobId");
        }
    }

    public record ChatRequest(List<AiChatClient.Message> messages, Double temperature) {}
    public record ChatResponse(String jobId, String content, String model, int inputTokens, int outputTokens, int totalTokens) {}
}
