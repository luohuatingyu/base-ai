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
import java.util.Map;

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
        worker.createContext("/llm/embeddings", this::respondToEmbeddings);
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

    /** 指定模型模式未填写模型类型时，应从模型能力中推导并传给 Worker。 */
    @Test
    void infersDirectModelTypeWhenItIsNotProvided() {
        LlmManagementService management = mock(LlmManagementService.class);
        LlmManagementService.WorkerCandidate candidate = new LlmManagementService.WorkerCandidate(
            "vision-provider", "https://vision.example/v1", List.of("key"), "vision-x", 3, "API_KEY", 45, "", "", List.of("vision_model"));
        when(management.resolveModel(8L, "", false, null)).thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), false, true));

        client(management).chat(null, null, List.of(new AiChatClient.Message("user", "describe")), 0D, false, null, 8L);

        assertTrue(requestBody.contains("\"model_type\":\"vision_model\""));
        verify(management).resolveModel(8L, "", false, null);
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

    /** 多模态消息应保持图片片段结构并完整下发到 Worker。 */
    @Test
    void forwardsMultimodalMessageContent() {
        LlmManagementService management = mock(LlmManagementService.class);
        when(management.resolveActive("chat", "vision_model"))
            .thenReturn(new LlmManagementService.WorkerRoute(List.of(), null, false));
        List<Map<String, Object>> content = List.of(
            Map.of("type", "text", "text", "请描述图片"),
            Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AAAA")));

        client(management).chat("chat", "vision_model",
            List.of(new AiChatClient.Message("user", content)), 0D, null, null, null);

        assertTrue(requestBody.contains("\"model_type\":\"vision_model\""));
        assertTrue(requestBody.contains("\"type\":\"image_url\""));
        assertTrue(requestBody.contains("data:image/png;base64,AAAA"));
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

    /** 向量调用必须固定解析 embedding 模型并保持批量响应顺序。 */
    @Test
    void embedsBatchWithResolvedEmbeddingModel() {
        LlmManagementService management = mock(LlmManagementService.class);
        LlmManagementService.WorkerCandidate candidate = new LlmManagementService.WorkerCandidate(
            "embedding-provider", "https://embedding.example/v1", List.of("key"), "embed-x", 4, "API_KEY", 45, "", "", List.of("embedding_model"));
        when(management.resolveModel(12L, "embedding_model", false, null))
            .thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), false, true));

        AiChatClient.EmbeddingResult result = client(management).embed(12L, List.of("first", "second"));

        assertEquals(List.of(List.of(1D, 0D), List.of(0D, 1D)), result.embeddings());
        assertEquals("worker-embedding", result.model());
        assertTrue(requestBody.contains("\"input\":[\"first\",\"second\"]"));
        assertTrue(requestBody.contains("\"providerCode\":\"embedding-provider\""));
        verify(management).resolveModel(12L, "embedding_model", false, null);
    }

    /** 向量路由模式必须固定按 embedding_model 解析候选并调用向量协议。 */
    @Test
    void embedsBatchWithConfiguredEmbeddingRoute() {
        LlmManagementService management = mock(LlmManagementService.class);
        LlmManagementService.WorkerCandidate candidate = new LlmManagementService.WorkerCandidate(
            "embedding-provider", "https://embedding.example/v1", List.of("key"), "embed-x", 4, "API_KEY", 45, "", "", List.of("embedding_model"));
        when(management.resolveActive("VECTOR", "embedding_model"))
            .thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), null, true));

        AiChatClient.EmbeddingResult result = client(management).embed("VECTOR", null, List.of("first", "second"));

        assertEquals(List.of(List.of(1D, 0D), List.of(0D, 1D)), result.embeddings());
        assertTrue(requestBody.contains("\"providerCode\":\"embedding-provider\""));
        verify(management).resolveActive("VECTOR", "embedding_model");
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

    /** 记录向量请求并返回与输入等长的确定性向量。 */
    private void respondToEmbeddings(HttpExchange exchange) throws IOException {
        assertEquals("HTTP/1.1", exchange.getProtocol());
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(internalToken, exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        byte[] response = "{\"embeddings\":[[1.0,0.0],[0.0,1.0]],\"model\":\"worker-embedding\"}"
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String parentTraceId;
    private String pythonTraceId;
}
