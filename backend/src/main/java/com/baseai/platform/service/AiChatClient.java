package com.baseai.platform.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.trace.TraceContextHolder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

@Service
public class AiChatClient {
    private final RestClient restClient;
    private final TaskTraceService taskTraceService;
    private final LlmManagementService llmManagementService;

    public AiChatClient(@Qualifier("pythonWorkerRestClient") RestClient restClient, TaskTraceService taskTraceService,
                        LlmManagementService llmManagementService) {
        this.restClient = restClient;
        this.taskTraceService = taskTraceService;
        this.llmManagementService = llmManagementService;
    }

    /** 调用 Python Worker：优先使用模型管理路由，未配置时回退 Worker 默认模型池。 */
    public ChatResult chat(String featureCode, String modelType, List<Message> messages, Double temperature) {
        TraceContextHolder.checkpoint();
        String normalizedFeature = featureCode == null || featureCode.isBlank() ? "chat" : featureCode.trim();
        LlmManagementService.WorkerRoute route = llmManagementService.resolve(normalizedFeature);
        String parentTraceId = TraceContextHolder.currentTraceId().orElse(null);
        String pythonTraceId = UUID.randomUUID().toString().replace("-", "");
        taskTraceService.registerPython(parentTraceId, pythonTraceId, "/llm/chat");
        try {
            ChatResult result = restClient.post().uri("/llm/chat")
                .header("X-Python-Trace-Id", pythonTraceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatRequest(normalizedFeature, modelType == null || modelType.isBlank() ? "text_model" : modelType,
                    messages, temperature == null ? 0D : temperature, route.candidates(), route.enableThinking())).retrieve().body(ChatResult.class);
            if (result == null) throw new BusinessException("模型服务返回空响应");
            taskTraceService.updatePython(pythonTraceId, "SUCCESS", null, null);
            TraceContextHolder.checkpoint();
            return result;
        } catch (RestClientException exception) {
            taskTraceService.updatePython(pythonTraceId, Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED", null, exception.getMessage());
            throw new BusinessException(502, "模型服务调用失败");
        }
    }

    public record Message(String role, String content) {}
    public record ChatRequest(String featureCode, @JsonProperty("model_type") String modelType, List<Message> messages, double temperature,
                              List<LlmManagementService.WorkerCandidate> candidates, Boolean enableThinking) {}
    public record ChatResult(String content, String model, int inputTokens, int outputTokens, int totalTokens) {}
}
