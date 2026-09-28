package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.LlmManagementService;
import com.baseai.platform.service.TaskTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** 验证工具决策客户端不会把生成模型误发到聊天协议。 */
class WorkflowAgentClientTest {
    /** 历史混合类型配置保留文本能力时可以进入工具决策聊天协议。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"image_model", "video_model"})
    void acceptsLegacyMixedCandidate(String type) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/llm/agent-step", exchange -> {
            byte[] response = "{\"content\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        TaskTraceService traces = mock(TaskTraceService.class);
        LlmManagementService management = mock(LlmManagementService.class);
        var candidate = new LlmManagementService.WorkerCandidate("provider", "https://example.com", List.of("key"),
            "legacy", 1, "PROVIDER", 60, null, null, List.of("text_model", type));
        when(management.resolveModel(1L, "text_model", false, null))
            .thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), false, true));
        var mapper = new ObjectMapper();
        var config = mapper.createObjectNode().put("modelId", 1L).put("modelType", "text_model");
        RestClient worker = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        var client = new WorkflowAgentClient(worker, management, traces, mapper, null);

        try {
            assertEquals("ok", client.step(config, List.of(), List.of()).content());
            verify(traces).registerPython(any(), any(), eq("/llm/agent-step"));
        } finally {
            server.stop(0);
        }
    }
    /** 显式类型和省略类型的直连都应在追踪与网络请求前被拒绝。 */
    @ParameterizedTest
    @CsvSource({"image_model,false", "video_model,false", "image_model,true", "video_model,true"})
    void rejectsGenerationBeforeAgentRequest(String type, boolean omitType) {
        RestClient worker = mock(RestClient.class);
        TaskTraceService traces = mock(TaskTraceService.class);
        LlmManagementService management = mock(LlmManagementService.class);
        var candidate = new LlmManagementService.WorkerCandidate("provider", "https://example.com", List.of("key"),
            "media", 1, "PROVIDER", 60, null, null, List.of(type));
        when(management.resolveModel(1L, omitType ? "" : type, false, null))
            .thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), false, true));
        var mapper = new ObjectMapper();
        var config = mapper.createObjectNode().put("modelId", 1L);
        if (!omitType) config.put("modelType", type);
        var client = new WorkflowAgentClient(worker, management, traces, mapper, null);

        var error = assertThrows(BusinessException.class, () -> client.step(config, List.of(), List.of()));

        assertEquals("llm.generationChatUnsupported", error.getMessageKey());
        verifyNoInteractions(worker, traces);
    }
}
