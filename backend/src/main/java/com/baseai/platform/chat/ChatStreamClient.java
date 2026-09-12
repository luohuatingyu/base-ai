package com.baseai.platform.chat;

import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.service.LlmManagementService;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.trace.TraceContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.io.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;

/** 有界读取 Worker SSE，保留内部签名、模型路由和完整任务生命周期。 */
@Service
public class ChatStreamClient {
    private final RestClient client;
    private final LlmManagementService management;
    private final TaskTraceService traces;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(2);

    /** 为流式接口设置连接及响应头超时，保留已有签名拦截器。 */
    public ChatStreamClient(@Qualifier("pythonWorkerRestClient") RestClient client, LlmManagementService management,
                            TaskTraceService traces, ObjectMapper mapper) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = client.mutate().requestFactory(factory).build();
        this.management = management; this.traces = traces; this.mapper = mapper;
    }

    /** 逐事件读取；消费者返回前不会预取下一事件。 */
    public void stream(ChatConversationService.Turn turn, EventConsumer consumer) {
        var settings = turn.settings();
        String feature = settings.featureCode() == null || settings.featureCode().isBlank() ? "chat" : settings.featureCode();
        var route = settings.modelId() == null ? management.resolveActive(feature, settings.modelType()) :
            management.resolveModel(settings.modelId(), settings.modelType(), Boolean.TRUE.equals(settings.enableThinking()), settings.thinkingLevel());
        String traceId = UUID.randomUUID().toString().replace("-", "");
        traces.registerPython(TraceContextHolder.currentTraceId().orElse(null), traceId, "/llm/chat/stream");
        try {
            client.post().uri("/llm/chat/stream").header("X-Python-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AiChatClient.ChatRequest(feature, settings.modelType(), turn.context(), 0,
                    route.candidates(), settings.enableThinking() == null ? route.enableThinking() : settings.enableThinking(),
                    settings.thinkingLevel(), route.routeConfigured()))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful() || response.getHeaders().getContentType() == null
                        || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(response.getHeaders().getContentType()))
                        throw new IOException("Invalid worker stream");
                    InputStream input = response.getBody();
                    TraceContextHolder.current().ifPresent(context -> context.runtime().registerCloseable(input));
                    AtomicLong lastRead = new AtomicLong(System.nanoTime());
                    long started = System.nanoTime();
                    ScheduledFuture<?> timeout = watchdog.scheduleAtFixedRate(() -> {
                        if (System.nanoTime() - lastRead.get() > TimeUnit.SECONDS.toNanos(30)
                            || System.nanoTime() - started > TimeUnit.MINUTES.toNanos(10)) {
                            try { input.close(); } catch (IOException ignored) { }
                        }
                    }, 5, 5, TimeUnit.SECONDS);
                    try (Reader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                        StringBuilder line = new StringBuilder();
                        long total = 0;
                        int character;
                        while ((character = reader.read()) != -1) {
                            if (++total > 32 * 1024 * 1024 || line.length() > 1024 * 1024) throw new IOException("Stream limit");
                            if (character != '\n') { line.append((char) character); continue; }
                            lastRead.set(System.nanoTime());
                            TraceContextHolder.checkpoint();
                            String value = line.toString().strip();
                            line.setLength(0);
                            if (!value.startsWith("data:")) continue;
                            JsonNode event = mapper.readTree(value.substring(5).strip());
                            if (event == null || !event.path("type").isTextual()) throw new IOException("Invalid stream event");
                            if ("error".equals(event.path("type").asText())) throw new IOException("Worker generation failed");
                            consumer.accept(event);
                            if ("done".equals(event.path("type").asText())) return null;
                        }
                        throw new IOException("Incomplete worker stream");
                    } finally { timeout.cancel(false); }
                });
            traces.updatePython(traceId, "SUCCESS", null, null);
        } catch (RuntimeException exception) {
            traces.updatePython(traceId, Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED", null, "Stream interrupted");
            throw exception;
        }
    }

    /** 关闭应用时释放超时调度器。 */
    @PreDestroy
    public void close() { watchdog.shutdownNow(); }

    @FunctionalInterface
    public interface EventConsumer {
        /** 消费事件并允许网络写入失败向上传播。 */
        void accept(JsonNode event) throws IOException;
    }
}
