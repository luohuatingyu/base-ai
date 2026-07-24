package com.baseai.platform.service;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.config.PythonWorkerRestClientConfig;
import com.baseai.platform.trace.TraceContext;
import com.baseai.platform.trace.TraceContextHolder;
import com.baseai.platform.trace.TraceRuntime;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            "managed-provider", "https://provider.example/v1", List.of("key"), "managed-model", 3, "API_KEY", 45, "reasoning_effort", "xhigh", List.of("text_model"));
        when(management.resolveActive("chat", "text_model")).thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), true, true));

        AiChatClient.ChatResult result = client(management).chat("chat", "text_model",
            List.of(new AiChatClient.Message("user", "hello")), 0D, null, null, null);

        assertEquals("worker-model", result.model());
        assertTrue(requestBody.contains("\"featureCode\":\"chat\""));
        assertTrue(requestBody.contains("\"providerCode\":\"managed-provider\""));
        assertTrue(requestBody.contains("\"enableThinking\":true"));
        assertTrue(requestBody.contains("\"routeConfigured\":true"));
        verify(management).resolveActive("chat", "text_model");
    }

    /** 单模型模式应按 modelId 解析单候选并下发，跳过能力路由。 */
    @Test
    void usesResolvedModelCandidateWhenModelIdProvided() {
        LlmManagementService management = mock(LlmManagementService.class);
        LlmManagementService.WorkerCandidate candidate = new LlmManagementService.WorkerCandidate(
            "youmi-openai", "https://youmi.example/v1", List.of("key"), "gpt-x", 3, "API_KEY", 45, "reasoning_effort", "high", List.of("text_model"));
        when(management.resolveModel(7L, "text_model", true, "HIGH")).thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), true, true));

        AiChatClient.ChatResult result = client(management).chat(null, "text_model",
            List.of(new AiChatClient.Message("user", "hello")), 0D, true, "HIGH", 7L);

        assertEquals("worker-model", result.model());
        assertTrue(requestBody.contains("\"providerCode\":\"youmi-openai\""));
        assertTrue(requestBody.contains("\"routeConfigured\":true"));
        assertTrue(requestBody.contains("\"enableThinking\":true"));
        verify(management).resolveModel(7L, "text_model", true, "HIGH");
    }

    /** 未配置能力路由时保持空候选和空开关，以触发 Worker 默认模型池回退。 */
    @Test
    void keepsDefaultPoolFallbackWhenFeatureRouteIsMissing() {
        LlmManagementService management = mock(LlmManagementService.class);
        when(management.resolveActive("chat", "text_model")).thenReturn(new LlmManagementService.WorkerRoute(List.of(), null, false));

        client(management).chat(null, null, List.of(new AiChatClient.Message("user", "hello")), null, null, null, null);

        assertTrue(requestBody.contains("\"featureCode\":\"chat\""));
        assertTrue(requestBody.contains("\"model_type\":\"text_model\""));
        assertTrue(requestBody.contains("\"candidates\":[]"));
        assertTrue(requestBody.contains("\"enableThinking\":null"));
    }

    /** 父任务 Trace ID 和新生成的 Python Trace ID 应通过新请求头传播。 */
    @Test
    void propagatesTraceHeadersToWorker() {
        LlmManagementService management = mock(LlmManagementService.class);
        when(management.resolveActive("chat", "text_model")).thenReturn(new LlmManagementService.WorkerRoute(List.of(), null, false));
        TraceRuntime runtime = new TraceRuntime("parent-trace");
        TraceContext context = new TraceContext("parent-trace", 1L, "AI 对话", "TEST", runtime.token(), runtime);

        try (TraceContextHolder.Scope ignored = TraceContextHolder.bind(context)) {
            client(management).chat("chat", "text_model", List.of(new AiChatClient.Message("user", "hello")), 0D, null, null, null);
        }

        assertEquals("parent-trace", parentTraceId);
        assertNotNull(pythonTraceId);
        assertTrue(!pythonTraceId.isBlank());
    }

    /** 创建使用 HTTP/1.1 Worker 客户端的待测对象。 */
    private AiChatClient client(LlmManagementService management) {
        PlatformProperties properties = new PlatformProperties();
        properties.getPythonWorker().setUrl("http://127.0.0.1:" + worker.getAddress().getPort());
        internalToken = "test-internal-token";
        properties.getPythonWorker().setInternalToken(internalToken);
        RestClient restClient = new PythonWorkerRestClientConfig().pythonWorkerRestClient(properties);
        return new AiChatClient(restClient, mock(TaskTraceService.class), management);
    }

    /** 记录 Worker 请求并返回最小 OpenAI-compatible 响应。 */
    private void respondToChat(HttpExchange exchange) throws IOException {
        assertEquals("HTTP/1.1", exchange.getProtocol());
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(internalToken, exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        parentTraceId = exchange.getRequestHeaders().getFirst("X-Parent-Trace-Id");
        pythonTraceId = exchange.getRequestHeaders().getFirst("X-Python-Trace-Id");
        byte[] response = "{\"content\":\"ok\",\"model\":\"worker-model\",\"inputTokens\":1,\"outputTokens\":1,\"totalTokens\":2}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String parentTraceId;
    private String pythonTraceId;
}
