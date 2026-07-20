package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Service
public class AiChatClient {
    private final RestClient restClient;

    public AiChatClient(@Qualifier("pythonWorkerRestClient") RestClient restClient) { this.restClient = restClient; }

    /** 调用 Python Worker 的通用 OpenAI-compatible 对话接口。 */
    public ChatResult chat(List<Message> messages, Double temperature) {
        try {
            ChatResult result = restClient.post().uri("/llm/chat")
                .body(new ChatRequest(messages, temperature == null ? 0D : temperature)).retrieve().body(ChatResult.class);
            if (result == null) throw new BusinessException("模型服务返回空响应");
            return result;
        } catch (RestClientException exception) {
            throw new BusinessException(502, "模型服务调用失败");
        }
    }

    public record Message(String role, String content) {}
    public record ChatRequest(List<Message> messages, double temperature) {}
    public record ChatResult(String content, String model, int inputTokens, int outputTokens, int totalTokens) {}
}
