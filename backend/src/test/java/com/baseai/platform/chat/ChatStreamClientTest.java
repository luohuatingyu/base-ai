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

    /** Agent 模式先执行授权工作流，再把工具结果交给模型并输出最终回答。 */
    @Test void executesAuthorizedWorkflowToolBeforeFinalAnswer() {
        var agent = mock(com.baseai.platform.workflow.WorkflowAgentClient.class);
        var workflows = mock(com.baseai.platform.workflow.WorkflowService.class);
        var executions = mock(com.baseai.platform.workflow.WorkflowExecutionService.class);
        var mapper = new ObjectMapper();
        var option = new com.baseai.platform.workflow.WorkflowModels.ChatToolOption("ORDERS", "查询订单",
            "查询订单状态", mapper.createObjectNode().put("type", "object"));
        when(workflows.chatToolOptions()).thenReturn(List.of(option));
        when(agent.step(any(), anyList(), anyList()))
            .thenReturn(new com.baseai.platform.workflow.WorkflowAgentClient.AgentStep("", List.of(
                new com.baseai.platform.workflow.WorkflowAgentClient.ToolCall("call-1", "ORDERS",
                    mapper.createObjectNode().put("id", "A-1"))), "agent-model"))
            .thenReturn(new com.baseai.platform.workflow.WorkflowAgentClient.AgentStep("订单已完成", List.of(), "agent-model"));
        when(executions.startPublished(eq("ORDERS"), anyMap()))
            .thenReturn(new com.baseai.platform.workflow.WorkflowModels.RunAccepted("run-1", "QUEUED"));
        when(executions.run("run-1")).thenReturn(new com.baseai.platform.workflow.WorkflowModels.RunView(
            "run-1", 1L, "ORDERS", 1, null, null, "CHAT_AGENT", "SUCCESS",
            mapper.createObjectNode(), mapper.createObjectNode().put("status", "done"), null, 1L, null,
            false, null, null, null, List.of()));

        client.close();
        client = new ChatStreamClient(RestClient.builder().baseUrl("http://127.0.0.1").build(),
            mock(LlmManagementService.class), traces, mapper, retry(), CircuitBreaker.ofDefaults("agent-test"),
            agent, workflows, executions);
        List<String> events = new ArrayList<>();
        client.stream(new ChatConversationService.Turn(1L, 1L, 2L,
            List.of(new AiChatClient.Message("user", "查询订单")),
            new ChatConversationService.Settings("text_model", "chat", null, false, "MEDIUM", "", List.of("ORDERS"))),
            event -> events.add(event.path("type").asText()));
        assertEquals(List.of("agent_tool_start", "agent_tool_done", "delta", "done"), events);
        verify(executions).startPublished(eq("ORDERS"), anyMap());
        verify(agent, times(2)).step(any(), anyList(), anyList());
    }

    /** 独立 Agent 工具只走接口工具执行器，不会被错误转发为工作流运行。 */
    @Test void executesIndependentAgentToolWithoutWorkflow() {
        var agent = mock(com.baseai.platform.workflow.WorkflowAgentClient.class);
        var apiTools = mock(com.baseai.platform.automation.ApiTriggerService.class);
        var mapper = new ObjectMapper();
        var option = new ChatAgentToolOption("HTTP_TRIGGER_7", "查询服务", "调用已配置接口",
            mapper.createObjectNode().put("type", "object"));
        when(apiTools.agentToolOptions(1L)).thenReturn(List.of(option));
        when(agent.step(any(), anyList(), anyList()))
            .thenReturn(new com.baseai.platform.workflow.WorkflowAgentClient.AgentStep("", List.of(
                new com.baseai.platform.workflow.WorkflowAgentClient.ToolCall("call-1", "HTTP_TRIGGER_7",
                    mapper.createObjectNode().put("orderId", "A-1"))), "agent-model"))
            .thenReturn(new com.baseai.platform.workflow.WorkflowAgentClient.AgentStep("接口已返回", List.of(), "agent-model"));
        when(apiTools.executeAgentTool(eq(1L), eq("HTTP_TRIGGER_7"), any()))
            .thenReturn(new com.baseai.platform.automation.ApiTriggerModels.ExecutionResult(200, 18, "{\"ok\":true}"));

        client.close();
        client = new ChatStreamClient(RestClient.builder().baseUrl("http://127.0.0.1").build(),
            mock(LlmManagementService.class), traces, mapper, retry(), CircuitBreaker.ofDefaults("agent-http-test"),
            agent, null, null, apiTools);
        List<String> events = new ArrayList<>();
        client.stream(new ChatConversationService.Turn(1L, 1L, 2L,
            List.of(new AiChatClient.Message("user", "查询")),
            new ChatConversationService.Settings("text_model", "chat", null, false, "MEDIUM", "",
                List.of(), List.of("HTTP_TRIGGER_7"), List.of())),
            event -> events.add(event.path("type").asText()));
        assertEquals(List.of("agent_tool_start", "agent_tool_done", "delta", "done"), events);
        verify(apiTools).executeAgentTool(eq(1L), eq("HTTP_TRIGGER_7"), any());
    }

    /** 构造真实客户端需要的会话上下文。 */
    private ChatConversationService.Turn turn() {
        return new ChatConversationService.Turn(1L, 1L, 2L, List.of(new AiChatClient.Message("user", "hello")),
            new ChatConversationService.Settings("text_model", "chat", null, false, "MEDIUM", ""));
    }

    /** Agent 测试使用一次调用，避免把模型重试混入编排断言。 */
    private Retry retry() {
        return Retry.of("agent-test", RetryConfig.custom().maxAttempts(1).retryExceptions(Exception.class).build());
    }
}
