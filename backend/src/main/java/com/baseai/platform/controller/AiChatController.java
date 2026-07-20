package com.baseai.platform.controller;

import com.baseai.platform.job.JobType;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.AiChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/chat")
@RequiredPermission("ai:chat:invoke")
public class AiChatController {
    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);
    private final AiChatClient client;

    public AiChatController(AiChatClient client) {
        this.client = client;
    }

    /** 建立任务上下文并代理一次通用模型对话。 */
    @PostMapping
    @JobType(value = "AI 对话", triggerEntry = "MANUAL", captureRequest = false)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("event=ai_chat_started message_count={}", request.messages() == null ? 0 : request.messages().size());
        AiChatClient.ChatResult result = client.chat(request.featureCode(), request.messages(), request.temperature());
        log.info("event=ai_chat_succeeded model={} total_tokens={}", result.model(), result.totalTokens());
        return new ChatResponse(com.baseai.platform.job.JobContextHolder.currentJobId().orElse(""), result.content(),
            result.model(), result.inputTokens(), result.outputTokens(), result.totalTokens());
    }

    public record ChatRequest(String featureCode, List<AiChatClient.Message> messages, Double temperature) {}
    public record ChatResponse(String jobId, String content, String model, int inputTokens, int outputTokens, int totalTokens) {}
}
