package com.baseai.platform.chat;

import com.baseai.platform.service.*;
import com.baseai.platform.controller.AiChatController;
import com.baseai.platform.trace.TraceContext;
import com.baseai.platform.trace.TraceContextHolder;
import com.baseai.platform.trace.TraceRuntime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 HTTP 服务验证 Java 增量读取与错误终态。 */
class ChatStreamClientTest {
    /** 流式生成请求必须在发出事件、登记用量或访问 Worker 前被拒绝。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"image_model", "video_model"})
    void rejectsGenerationTypeBeforeStreaming(String type) {
        var turn = new ChatConversationService.Turn(1L, 1L, 2L, List.of(new AiChatClient.Message("user", "test")),
            new ChatConversationService.Settings(type, "chat", null, false, "", ""));
        var error = assertThrows(com.baseai.platform.common.BusinessException.class,
            () -> client.stream(turn, event -> fail("不得向客户端发送生成模型的聊天事件")));
        assertEquals("llm.generationChatUnsupported", error.getMessageKey());
        verifyNoInteractions(traces, usage);
        assertNull(requestBody);
    }
    HttpServer server;
    ChatStreamClient client;
    TaskTraceService traces;
    LlmManagementService management;
    LlmUsageService usage;
    CountDownLatch delivered;
    String response;
    String requestBody;
    int requests;
    int status = 200;
    String contentType = "text/event-stream";
    boolean sendDelta = true;

    /** 配置可等待首段消费后再发送下一段的 Worker 协议端点。 */
    @BeforeEach void setup() throws Exception {
        delivered = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/llm/chat/stream", exchange -> {
            requests++;
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, 0);
            try (var output = exchange.getResponseBody()) {
                if (sendDelta) output.write("data: {\"type\":\"delta\",\"content\":\"你好\"}\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { if (sendDelta) assertTrue(delivered.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        management = mock(LlmManagementService.class);
        var candidate = new LlmManagementService.WorkerCandidate("test-provider", "http://provider.example",
            List.of("key"), "test-model", 2, "PROVIDER", 30, null, null, List.of("text_model"));
        when(management.resolveActive("chat", "text_model")).thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), false, true));
        traces = mock(TaskTraceService.class);
        usage = mock(LlmUsageService.class);
        when(usage.start(any())).thenReturn("usage-id");
        Retry retry = Retry.of("test", RetryConfig.custom().maxAttempts(3).retryExceptions(Exception.class)
            .ignoreExceptions(IllegalStateException.class).build());
        client = new ChatStreamClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
            management, traces, new ObjectMapper(), retry, CircuitBreaker.ofDefaults("test"), usage);
    }

    /** 回收测试 HTTP 服务器及后台超时调度器。 */
    @AfterEach void close() { client.close(); server.stop(0); }

    /** 最终响应之前已将中文增量交给调用者，且上下文传输正确。 */
    @Test void deliversBeforeCompletion() {
        response = "data: {\"type\":\"done\",\"model\":\"test-model\",\"totalTokens\":3}\n\n";
        List<String> events = new ArrayList<>();
        List<com.fasterxml.jackson.databind.JsonNode> payloads = new ArrayList<>();
        client.stream(turn(), event -> { events.add(event.path("type").asText()); payloads.add(event); delivered.countDown(); });
        assertEquals(List.of("model", "delta", "done"), events);
        assertEquals("test-model", payloads.get(0).path("model").asText());
        assertTrue(requestBody.contains("\"role\":\"user\""));
        assertTrue(requestBody.contains("\"model_type\":\"text_model\""));
        verify(traces).updatePython(anyString(), eq("SUCCESS"), isNull(), isNull());
    }

    /** 上游提前关闭不能被错误标记为成功。 */
    @Test void rejectsMissingCompletion() {
        response = "";
        assertThrows(RuntimeException.class, () -> client.stream(turn(), event -> delivered.countDown()));
        verify(traces).updatePython(anyString(), eq("FAILED"), isNull(), eq("Stream interrupted"));
        verify(traces, never()).updatePython(anyString(), eq("SUCCESS"), any(), any());
    }

    /** 上游已返回 usage 后即使连接中断，也必须把已知 Token 写入中断记录。 */
    @Test void preservesUsageWhenStreamInterrupts() {
        response = "data: {\"type\":\"usage\",\"inputTokens\":5,\"outputTokens\":2,\"totalTokens\":7}\n\n";
        assertThrows(RuntimeException.class, () -> client.stream(turn(), event -> delivered.countDown()));
        var completion = org.mockito.ArgumentCaptor.forClass(LlmUsageService.Completion.class);
        verify(usage).finish(anyString(), completion.capture());
        assertEquals("INTERRUPTED", completion.getValue().status());
        assertEquals(5L, completion.getValue().inputTokens());
        assertEquals(2L, completion.getValue().outputTokens());
        assertEquals(7L, completion.getValue().totalTokens());
    }

    /** 公开聊天适配器复用真实 SSE 读取，并把请求温度和来源传给 Worker/用量记录。 */
    @Test void streamsPublicChatRequest() {
        response = "data: {\"type\":\"done\",\"model\":\"test-model\",\"totalTokens\":3}\n\n";
        var request = new AiChatClient.ChatRequest("chat", "text_model",
            List.of(new AiChatClient.Message("user", "hello")), 0.7D, List.of(), false, "MEDIUM", false);
        client.stream(request, "CHAT_STREAM", event -> delivered.countDown());
        assertTrue(requestBody.contains("\"temperature\":0.7"));
        var start = org.mockito.ArgumentCaptor.forClass(LlmUsageService.Start.class);
        verify(usage).start(start.capture());
        assertEquals("CHAT_STREAM", start.getValue().source());
    }


    /** 请求线程退出后，真实流式客户端写入的用量记录仍使用原始 Trace ID，成功和异常路径均清理线程状态。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void publicAsyncStreamPersistsRequestTraceAndRestoresWorkerContext(boolean success) throws Exception {
        JdbcTemplate jdbc = usePersistentUsage();
        response = success ? "data: {\"type\":\"done\",\"model\":\"test-model\",\"totalTokens\":3}\n\n" : "";
        delivered.countDown();
        ResponseEntity<StreamingResponseBody> pending = publicResponse("request-trace");
        assertTrue(TraceContextHolder.current().isEmpty());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String body = executor.submit(() -> {
                TraceContext previous = context("worker-previous");
                try (var ignored = TraceContextHolder.bind(previous)) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    pending.getBody().writeTo(output);
                    assertSame(previous, TraceContextHolder.current().orElseThrow());
                    assertEquals("worker-previous", org.slf4j.MDC.get("traceId"));
                    return output.toString(StandardCharsets.UTF_8);
                }
            }).get(5, TimeUnit.SECONDS);
            assertTrue(body.contains("\"traceId\":\"request-trace\""));
            assertTrue(body.contains(success ? "\"type\":\"done\"" : "\"type\":\"error\""));
            assertTrue(executor.submit(() -> TraceContextHolder.current().isEmpty()).get(5, TimeUnit.SECONDS));
            var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).update(contains("INSERT INTO llm_usage_record"), args.capture());
            assertEquals("request-trace", args.getValue()[1]);
            assertEquals(7L, args.getValue()[3]);
            verify(traces).registerPython(eq("request-trace"), eq((String) args.getValue()[2]), eq("/llm/chat/stream"));
        } finally { executor.shutdownNow(); }
    }

    /** 同时生成的两个异步响应不能互相使用对方的链路编号。 */
    @Test
    void concurrentPublicResponsesKeepDistinctTraceIds() throws Exception {
        JdbcTemplate jdbc = usePersistentUsage();
        response = "data: {\"type\":\"done\",\"model\":\"test-model\"}\n\n";
        delivered.countDown();
        var first = publicResponse("request-first");
        var second = publicResponse("request-second");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (var pending : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    pending.getBody().writeTo(output);
                    assertTrue(TraceContextHolder.current().isEmpty());
                    assertNull(org.slf4j.MDC.get("traceId"));
                    return output.toString(StandardCharsets.UTF_8);
                }));
            }
            start.countDown();
            assertTrue(results.get(0).get(5, TimeUnit.SECONDS).contains("request-first"));
            assertTrue(results.get(1).get(5, TimeUnit.SECONDS).contains("request-second"));
            var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbc, times(2)).update(contains("INSERT INTO llm_usage_record"), args.capture());
            assertEquals(Set.of("request-first", "request-second"),
                new HashSet<>(args.getAllValues().stream().map(values -> values[1]).toList()));
        } finally { executor.shutdownNow(); }
    }

    /** 首帧输出失败也必须恢复工作线程的上下文，不得触发后续模型调用。 */
    @Test
    void disconnectedOutputRestoresTraceWithoutCallingWorker() throws Exception {
        var pending = publicResponse("disconnected-trace");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                assertThrows(IOException.class, () -> pending.getBody().writeTo(new OutputStream() {
                    /** 模拟浏览器在首帧前断开连接。 */
                    @Override public void write(int value) throws IOException { throw new IOException("disconnected"); }
                }));
                assertTrue(TraceContextHolder.current().isEmpty());
                assertNull(org.slf4j.MDC.get("traceId"));
            }).get(5, TimeUnit.SECONDS);
            verifyNoInteractions(usage);
        } finally { executor.shutdownNow(); }
    }

    /** 仅替换数据库边界，保留控制器、流式 HTTP 客户端和用量持久化逻辑。 */
    private JdbcTemplate usePersistentUsage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(7L);
        client.close();
        client = new ChatStreamClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
            management, traces, new ObjectMapper(), retry(), CircuitBreaker.ofDefaults("trace-test"),
            new LlmUsageService(jdbc));
        return jdbc;
    }

    /** 在独立请求作用域中生成延迟执行的 SSE 响应。 */
    private ResponseEntity<StreamingResponseBody> publicResponse(String traceId) {
        var controller = new AiChatController(mock(AiChatClient.class), management, client, new ObjectMapper());
        try (var ignored = TraceContextHolder.bind(context(traceId))) {
            return controller.chat(new AiChatController.ChatRequest("text_model", "chat",
                List.of(new AiChatClient.Message("user", "hello")), 0D, false, "MEDIUM", null, true));
        }
    }

    /** 创建具有独立日志和取消状态的请求上下文。 */
    private TraceContext context(String traceId) {
        TraceRuntime runtime = new TraceRuntime(traceId);
        return new TraceContext(traceId, 7L, "AI_CHAT", "WEB_UI", runtime.token(), runtime);
    }

    @Test
    void resolvesSelectedModelAndDefaultThinking() {
        when(management.resolveModel(7L, "text_model", false, "HIGH"))
            .thenReturn(new LlmManagementService.WorkerRoute(List.of(), true, true));
        response = "data: {\"type\":\"done\"}\n\n";
        var turn = new ChatConversationService.Turn(1L, 1L, 2L, turn().context(),
            new ChatConversationService.Settings("text_model", " ", 7L, null, "HIGH", ""));
        client.stream(turn, event -> delivered.countDown());
        verify(management).resolveModel(7L, "text_model", false, "HIGH");
        verify(management, never()).resolveActive(anyString(), anyString());
        assertTrue(requestBody.contains("\"model_type\":\"text_model\""));
        assertEquals(1, requests);
        verify(traces).updatePython(anyString(), eq("SUCCESS"), isNull(), isNull());
    }

    /** 构造真实客户端需要的会话上下文。 */
    private ChatConversationService.Turn turn() {
        return new ChatConversationService.Turn(1L, 1L, 2L, List.of(new AiChatClient.Message("user", "hello")),
            new ChatConversationService.Settings("text_model", "chat", null, false, "MEDIUM", ""));
    }

    /** 关闭路由模型事件，使只验证增量与终态的用例不受路由公告影响。 */
    private void withoutRoutedModelEvent() {
        when(management.resolveActive("chat", "text_model")).thenReturn(new LlmManagementService.WorkerRoute(List.of(), false, true));
    }

    /** 路由配置错误不属于 Worker 故障，不重试、不计入熔断且保留业务错误。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"llm.defaultPoolUnavailable", "llm.selectedPoolUnavailable"})
    void preservesRouteFailureWithoutRetryOrWorkerCall(String key) {
        var management = mock(LlmManagementService.class);
        var failure = new com.baseai.platform.common.BusinessException(key);
        when(management.resolveActive("chat", "text_model")).thenThrow(failure);
        var breaker = CircuitBreaker.ofDefaults("route-failure");
        client.close();
        client = new ChatStreamClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
            management, traces, new ObjectMapper(), Retry.ofDefaults("route-failure"), breaker);

        assertSame(failure, assertThrows(com.baseai.platform.common.BusinessException.class,
            () -> client.stream(turn(), event -> fail("Route failure must not emit model events"))));
        verify(management, times(1)).resolveActive("chat", "text_model");
        verifyNoInteractions(traces);
        assertNull(requestBody);
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }

    /** Agent 测试使用一次调用，避免把模型重试混入编排断言。 */
    private Retry retry() {
        return Retry.of("agent-test", RetryConfig.custom().maxAttempts(1).retryExceptions(Exception.class).build());
    }
}
