package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.job.JobContextHolder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

@Service
public class AiChatClient {
    private final RestClient restClient;
    private final TaskJobService taskJobService;

    public AiChatClient(@Qualifier("pythonWorkerRestClient") RestClient restClient, TaskJobService taskJobService) {
        this.restClient = restClient;
        this.taskJobService = taskJobService;
    }

    /** 调用 Python Worker 的通用 OpenAI-compatible 对话接口。 */
    public ChatResult chat(String featureCode, List<Message> messages, Double temperature) {
        JobContextHolder.checkpoint();
        String parentJobId = JobContextHolder.currentJobId().orElse(null);
        String pythonJobId = UUID.randomUUID().toString().replace("-", "");
        taskJobService.registerPython(parentJobId, pythonJobId, "/llm/chat");
        try {
            ChatResult result = restClient.post().uri("/llm/chat")
                .header("X-Python-Job-Id", pythonJobId)
                .body(new ChatRequest(featureCode == null || featureCode.isBlank() ? "chat" : featureCode,
                    messages, temperature == null ? 0D : temperature, List.of(), null)).retrieve().body(ChatResult.class);
            if (result == null) throw new BusinessException("模型服务返回空响应");
            taskJobService.updatePython(pythonJobId, "SUCCESS", null, null);
            JobContextHolder.checkpoint();
            return result;
        } catch (RestClientException exception) {
            taskJobService.updatePython(pythonJobId, Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED", null, exception.getMessage());
            throw new BusinessException(502, "模型服务调用失败");
        }
    }

    public record Message(String role, String content) {}
    public record ChatRequest(String featureCode, List<Message> messages, double temperature,
                              List<LlmManagementService.WorkerCandidate> candidates, Boolean enableThinking) {}
    public record ChatResult(String content, String model, int inputTokens, int outputTokens, int totalTokens) {}
}
