package com.baseai.platform.service;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.config.PythonWorkerRestClientConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatClientTest {
    private HttpServer worker;
    private String requestBody;
    private String internalToken;

    @BeforeEach
    void startWorker() throws IOException {
        worker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        worker.createContext("/llm/chat", this::respondToChat);
        worker.start();
    }

    @AfterEach
    void stopWorker() {
        worker.stop(0);
    }

    /** 已配置的能力路由应将模型管理候选和思考开关下发给 Worker。 */
    @Test
    void usesManagedCandidatesForConfiguredFeature() {
        LlmManagementService management = mock(LlmManagementService.class);
        LlmManagementService.WorkerCandidate candidate = new LlmManagementService.WorkerCandidate(
            "managed-provider", "https://provider.example/v1", List.of("key"), "managed-model", 3, "API_KEY", 45);
        when(management.resolve("chat")).thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), true));

        AiChatClient.ChatResult result = client(management).chat("chat", "text_model",
            List.of(new AiChatClient.Message("user", "hello")), 0D);

        assertEquals("worker-model", result.model());
        assertTrue(requestBody.contains("\"featureCode\":\"chat\""));
        assertTrue(requestBody.contains("\"providerCode\":\"managed-provider\""));
        assertTrue(requestBody.contains("\"enableThinking\":true"));
        verify(management).resolve("chat");
    }

    /** 未配置能力路由时保持空候选和空开关，以触发 Worker 默认模型池回退。 */
    @Test
    void keepsDefaultPoolFallbackWhenFeatureRouteIsMissing() {
        LlmManagementService management = mock(LlmManagementService.class);
        when(management.resolve("chat")).thenReturn(new LlmManagementService.WorkerRoute(List.of(), null));

        client(management).chat(null, null, List.of(new AiChatClient.Message("user", "hello")), null);

        assertTrue(requestBody.contains("\"featureCode\":\"chat\""));
        assertTrue(requestBody.contains("\"model_type\":\"text_model\""));
        assertTrue(requestBody.contains("\"candidates\":[]"));
        assertTrue(requestBody.contains("\"enableThinking\":null"));
    }

    /** 创建使用 HTTP/1.1 Worker 客户端的待测对象。 */
    private AiChatClient client(LlmManagementService management) {
        PlatformProperties properties = new PlatformProperties();
        properties.getPythonWorker().setUrl("http://127.0.0.1:" + worker.getAddress().getPort());
        internalToken = "test-internal-token";
        properties.getPythonWorker().setInternalToken(internalToken);
        RestClient restClient = new PythonWorkerRestClientConfig().pythonWorkerRestClient(properties);
        return new AiChatClient(restClient, mock(TaskJobService.class), management);
    }

    /** 记录 Worker 请求并返回最小 OpenAI-compatible 响应。 */
    private void respondToChat(HttpExchange exchange) throws IOException {
        assertEquals("HTTP/1.1", exchange.getProtocol());
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(internalToken, exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        byte[] response = "{\"content\":\"ok\",\"model\":\"worker-model\",\"inputTokens\":1,\"outputTokens\":1,\"totalTokens\":2}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
