package com.baseai.platform.chat;

import com.baseai.platform.service.*;
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
    HttpServer server;
    ChatStreamClient client;
    TaskTraceService traces;
    CountDownLatch delivered;
    String response;
    String requestBody;
    int requests;
    int status = 200;
    String contentType = "text/event-stream";
    boolean sendDelta = true;
    LlmManagementService management;

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
        when(management.resolveActive("chat", "text_model")).thenReturn(new LlmManagementService.WorkerRoute(List.of(), false, true));
        traces = mock(TaskTraceService.class);
        Retry retry = Retry.of("test", RetryConfig.custom().maxAttempts(3).retryExceptions(Exception.class)
            .ignoreExceptions(IllegalStateException.class).build());
        client = new ChatStreamClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
            management, traces, new ObjectMapper(), retry, CircuitBreaker.ofDefaults("test"));
    }

    /** 回收测试 HTTP 服务器及后台超时调度器。 */
    @AfterEach void close() { client.close(); server.stop(0); }

    /** 最终响应之前已将中文增量交给调用者，且上下文传输正确。 */
    @Test void deliversBeforeCompletion() {
        response = "data: {\"type\":\"done\",\"model\":\"test-model\",\"totalTokens\":3}\n\n";
        List<String> events = new ArrayList<>();
        client.stream(turn(), event -> { events.add(event.path("type").asText()); delivered.countDown(); });
        assertEquals(List.of("delta", "done"), events);
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

    /** 首段之后的协议错误不能重试，避免重复内容和模型副作用。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "data: {\"type\":\"error\"}\n\n", "data: null\n\n", "data: {}\n\n", "data: {broken\n\n"
    })
    void malformedEventAfterDeltaNeverRetries(String invalid) {
        response = invalid;
        List<String> deltas = new ArrayList<>();
        assertThrows(RuntimeException.class, () -> client.stream(turn(), event -> {
            deltas.add(event.path("content").asText()); delivered.countDown();
        }));
        assertEquals(List.of("你好"), deltas);
        assertEquals(1, requests);
        verify(traces, never()).updatePython(anyString(), eq("SUCCESS"), any(), any());
        verify(traces).updatePython(anyString(), eq("FAILED"), isNull(), eq("Stream interrupted"));
    }

    /** 首段之前的无效 HTTP 响应按重试配置处理且不产生输出。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {503, 200})
    void rejectsInvalidResponseBeforeAnyDelta(int httpStatus) {
        status = httpStatus; contentType = "application/json"; sendDelta = false; response = "{}";
        List<String> events = new ArrayList<>();
        assertThrows(RuntimeException.class, () -> client.stream(turn(), event -> events.add(event.toString())));
        assertTrue(events.isEmpty());
        assertEquals(3, requests);
        verify(traces, times(3)).updatePython(anyString(), eq("FAILED"), isNull(), eq("Stream interrupted"));
    }

    /** 单模型选择使用模型路由，未指定思考参数时保留路由默认值。 */
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
}
