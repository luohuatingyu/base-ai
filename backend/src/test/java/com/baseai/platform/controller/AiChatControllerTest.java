package com.baseai.platform.controller;

import com.baseai.platform.chat.ChatStreamClient;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.service.LlmManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证公开聊天接口由 stream 参数切换 JSON 与 SSE，并保持统一事件格式。 */
class AiChatControllerTest {
    /** stream=false 或缺省时返回一次性 JSON，不调用流式客户端。 */
    @Test
    void returnsJsonWhenStreamingDisabled() {
        AiChatClient chat = mock(AiChatClient.class);
        when(chat.chat(any(), any(), anyList(), any(), any(), any(), any()))
            .thenReturn(new AiChatClient.ChatResult("answer", "model-a", 2, 3, 5));
        AiChatController controller = new AiChatController(chat, mock(LlmManagementService.class),
            mock(ChatStreamClient.class), new ObjectMapper());

        ResponseEntity<StreamingResponseBody> response = controller.chat(new AiChatController.ChatRequest("text_model", "chat",
            List.of(new AiChatClient.Message("user", "hello")), 0D, false, "MEDIUM", null, false));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("\"content\":\"answer\""));
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    /** stream=true 返回可执行 SSE，首帧、增量和终止帧按顺序即时输出。 */
    @Test
    void returnsSseWhenStreamingEnabled() throws Exception {
        AiChatClient chat = mock(AiChatClient.class);
        ChatStreamClient streams = mock(ChatStreamClient.class);
        doAnswer(invocation -> {
            ChatStreamClient.EventConsumer consumer = invocation.getArgument(3);
            consumer.accept(new ObjectMapper().readTree("{\"type\":\"model\",\"model\":\"model-a\",\"providerCode\":\"provider-a\"}"));
            consumer.accept(new ObjectMapper().readTree("{\"type\":\"delta\",\"content\":\"答\"}"));
            consumer.accept(new ObjectMapper().readTree("{\"type\":\"done\",\"model\":\"model-a\",\"inputTokens\":2,\"outputTokens\":1,\"totalTokens\":3}"));
            return null;
        }).when(streams).stream(any(AiChatClient.ChatRequest.class), isNull(), eq("CHAT_STREAM"), any());
        AiChatController controller = new AiChatController(chat, mock(LlmManagementService.class), streams, new ObjectMapper());

        ResponseEntity<StreamingResponseBody> result = controller.chat(new AiChatController.ChatRequest("text_model", "chat",
            List.of(new AiChatClient.Message("user", "hello")), 0D, false, "MEDIUM", null, true));
        ResponseEntity<StreamingResponseBody> response = result;
        assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ((StreamingResponseBody) response.getBody()).writeTo(output);
        String body = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"type\":\"start\""));
        assertTrue(body.contains("\"type\":\"model\""));
        assertTrue(body.contains("\"model\":\"model-a\""));
        assertTrue(body.contains("\"type\":\"delta\""));
        assertTrue(body.contains("\"type\":\"done\""));
    }

    /** 公开流上游失败时只返回安全错误事件，不泄露供应商异常正文。 */
    @Test
    void hidesPublicStreamFailureDetails() throws Exception {
        ChatStreamClient streams = mock(ChatStreamClient.class);
        doThrow(new IllegalStateException("provider-secret"))
            .when(streams).stream(any(AiChatClient.ChatRequest.class), isNull(), eq("CHAT_STREAM"), any());
        AiChatController controller = new AiChatController(mock(AiChatClient.class), mock(LlmManagementService.class),
            streams, new ObjectMapper());
        ResponseEntity<StreamingResponseBody> result = controller.chat(new AiChatController.ChatRequest("text_model", "chat",
            List.of(new AiChatClient.Message("user", "hello")), 0D, false, "MEDIUM", null, true));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ((StreamingResponseBody) result.getBody()).writeTo(output);
        String body = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"type\":\"error\""));
        assertFalse(body.contains("provider-secret"));
    }
}
