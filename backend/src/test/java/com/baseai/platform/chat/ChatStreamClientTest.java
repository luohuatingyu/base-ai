package com.baseai.platform.chat;

import com.baseai.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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

    /** 配置可等待首段消费后再发送下一段的 Worker 协议端点。 */
    @BeforeEach void setup() throws Exception {
        delivered = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/llm/chat/stream", exchange -> {
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("data: {\"type\":\"delta\",\"content\":\"你好\"}\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { assertTrue(delivered.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        var management = mock(LlmManagementService.class);
        when(management.resolveActive("chat", "text_model")).thenReturn(new LlmManagementService.WorkerRoute(List.of(), false, true));
        traces = mock(TaskTraceService.class);
        client = new ChatStreamClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
            management, traces, new ObjectMapper());
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

    /** 构造真实客户端需要的会话上下文。 */
    private ChatConversationService.Turn turn() {
        return new ChatConversationService.Turn(1L, 1L, 2L, List.of(new AiChatClient.Message("user", "hello")),
            new ChatConversationService.Settings("text_model", "chat", null, false, "MEDIUM", ""));
    }
}
