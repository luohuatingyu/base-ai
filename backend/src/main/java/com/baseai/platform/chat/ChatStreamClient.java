package com.baseai.platform.chat;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.service.LlmManagementService;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.service.LlmUsageService;
import com.baseai.platform.trace.TraceContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.retry.Retry;
import io.vavr.control.Try;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.io.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import jakarta.annotation.PreDestroy;

/** 有界读取 Worker SSE，保留内部签名、模型路由和完整任务生命周期。 */
@Service
public class ChatStreamClient {
    private final RestClient client;
    private final LlmManagementService management;
    private final TaskTraceService traces;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(2);
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final LlmUsageService llmUsageService;

    /** 为流式接口设置连接及响应头超时。 */
    @Autowired
    public ChatStreamClient(@Qualifier("pythonWorkerRestClient") RestClient client, LlmManagementService management,
                            TaskTraceService traces, ObjectMapper mapper, Retry pythonWorkerRetry, 
                            CircuitBreaker pythonWorkerCircuitBreaker, LlmUsageService llmUsageService) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = client.mutate().requestFactory(factory).build();
        this.management = management; this.traces = traces; this.mapper = mapper;
        this.retry = pythonWorkerRetry;
        this.circuitBreaker = pythonWorkerCircuitBreaker;
        this.llmUsageService = llmUsageService;
    }

    /** 保留基础流测试使用的构造方式，普通聊天不需要工作流依赖。 */
    public ChatStreamClient(@Qualifier("pythonWorkerRestClient") RestClient client, LlmManagementService management,
                            TaskTraceService traces, ObjectMapper mapper, Retry pythonWorkerRetry,
                            CircuitBreaker pythonWorkerCircuitBreaker) {
        this(client, management, traces, mapper, pythonWorkerRetry, pythonWorkerCircuitBreaker, null);
    }

    /** 逐事件读取；消费者返回前不会预取下一事件。使用重试和熔断保护。 */
    public void stream(ChatConversationService.Turn turn, EventConsumer consumer) {
        stream(turn, consumer, "STREAM", 0D);
    }

    /** 为公开聊天接口复用同一套 SSE、重试和 Token 记录逻辑。 */
    public void stream(AiChatClient.ChatRequest request, String source, EventConsumer consumer) {
        stream(request, null, source, consumer);
    }

    /** 为公开聊天接口保留指定模型直连能力。 */
    public void stream(AiChatClient.ChatRequest request, Long modelId, String source, EventConsumer consumer) {
        var settings = new ChatConversationService.Settings(request.modelType(), request.featureCode(), modelId,
            request.enableThinking(), request.thinkingLevel(), "");
        var turn = new ChatConversationService.Turn(null, null, null, request.messages(), settings);
        stream(turn, consumer, source, request.temperature());
    }

    /** 执行指定来源和温度的流式调用，保持会话和公开接口共用生命周期。 */
    private void stream(ChatConversationService.Turn turn, EventConsumer consumer, String source, Double temperature) {
        LlmManagementService.requireChatCapabilities(turn.settings().modelType(), null);
        // 路由配置错误不能作为 Worker 故障重试或计入熔断；本轮重试固定使用同一快照。
        var settings = turn.settings();
        String feature = settings.featureCode() == null || settings.featureCode().isBlank() ? "chat" : settings.featureCode();
        var route = settings.modelId() == null ? management.resolveActive(feature, settings.modelType()) :
            management.resolveModel(settings.modelId(), settings.modelType(), Boolean.TRUE.equals(settings.enableThinking()), settings.thinkingLevel());
        LlmManagementService.requireChatCapabilities(settings.modelType(), route);
        if (route != null && route.candidates() != null && !route.candidates().isEmpty()) {
            var selected = route.candidates().get(0);
            if (selected != null && selected.model() != null && !selected.model().isBlank()) {
                // 路由在连接上游前已经确定优先模型，先通知前端；done 事件仍以实际上游模型为准。
                var modelEvent = mapper.createObjectNode().put("type", "model").put("model", selected.model());
                if (selected.providerCode() != null) modelEvent.put("providerCode", selected.providerCode());
                emit(consumer, modelEvent);
            }
        }
        AtomicBoolean streamStarted = new AtomicBoolean(false);
        Supplier<Void> supplier = () -> {
            executeStream(turn, event -> {
                if ("delta".equals(event.path("type").asText())) streamStarted.set(true);
                consumer.accept(event);
            }, streamStarted, feature, route, source, temperature);
            return null;
        };
        
        // 使用熔断器装饰
        Supplier<Void> circuitBreakerSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
        
        // 使用重试装饰
        Supplier<Void> retrySupplier = Retry.decorateSupplier(retry, circuitBreakerSupplier);
        
        try {
            Try.ofSupplier(retrySupplier).get();
        } catch (CallNotPermittedException e) {
            throw new RuntimeException("Python Worker circuit breaker is open", e);
        } catch (Exception e) {
            throw new RuntimeException("Python Worker call failed after retries", e);
        }
    }
    
    /** 实际执行流式调用。 */
    private void executeStream(ChatConversationService.Turn turn, EventConsumer consumer, AtomicBoolean streamStarted,
                               String feature, LlmManagementService.WorkerRoute route, String source,
                               Double temperature) {
        var settings = turn.settings();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        traces.registerPython(TraceContextHolder.currentTraceId().orElse(null), traceId, "/llm/chat/stream");
        String usageId = llmUsageService == null ? null : llmUsageService.start(
            LlmUsageService.Start.current(traceId, turn.owner(), turn.conversationId(), source, feature, settings.modelType()));
        AtomicBoolean usageFinished = new AtomicBoolean(false);
        AtomicReference<TokenUsage> latestUsage = new AtomicReference<>();
        try {
            client.post().uri("/llm/chat/stream").header("X-Python-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AiChatClient.ChatRequest(feature, settings.modelType(), turn.context(), temperature == null ? 0D : temperature,
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
                            if ("usage".equals(event.path("type").asText())) {
                                latestUsage.set(mergeUsage(latestUsage.get(), event));
                                continue;
                            }
                            if ("delta".equals(event.path("type").asText()) && llmUsageService != null)
                                llmUsageService.markFirstToken(usageId, Instant.now());
                            consumer.accept(event);
                            if ("done".equals(event.path("type").asText())) {
                                TokenUsage usage = mergeUsage(latestUsage.get(), event);
                                latestUsage.set(usage);
                                if (llmUsageService != null) llmUsageService.finish(usageId, new LlmUsageService.Completion(
                                    "SUCCESS", providerFor(route, usage.model()), usage.model(), true, usage.inputTokens(),
                                    usage.outputTokens(), usage.totalTokens(), null, null, null));
                                usageFinished.set(true);
                                return null;
                            }
                        }
                        throw new IOException("Incomplete worker stream");
                    } finally { timeout.cancel(false); }
                });
            traces.updatePython(traceId, "SUCCESS", null, null);
        } catch (RuntimeException exception) {
            traces.updatePython(traceId, Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED", null, "Stream interrupted");
            if (!usageFinished.get() && llmUsageService != null) llmUsageService.finish(usageId,
                new LlmUsageService.Completion(Thread.currentThread().isInterrupted() ? "CANCELLED" : streamStarted.get() ? "INTERRUPTED" : "FAILED",
                    latestUsage.get() == null ? null : providerFor(route, latestUsage.get().model()),
                    latestUsage.get() == null ? null : latestUsage.get().model(), streamStarted.get(),
                    latestUsage.get() == null ? null : latestUsage.get().inputTokens(),
                    latestUsage.get() == null ? null : latestUsage.get().outputTokens(),
                    latestUsage.get() == null ? null : latestUsage.get().totalTokens(), exception.getClass().getSimpleName(), null, null));
            if (streamStarted.get()) throw new StreamAlreadyStartedException(exception);
            throw exception;
        }
    }

    /** 根据实际返回模型匹配供应商，避免把路由首项当成实际调用项。 */
    private String providerFor(LlmManagementService.WorkerRoute route, String model) {
        return LlmManagementService.providerFor(route, model);
    }

    /** 读取 Worker 事件中的兼容 Token 字段。 */
    private Long token(JsonNode event, String... names) {
        for (String name : names) {
            JsonNode value = event.get(name);
            if (value != null && value.isNumber() && value.isIntegralNumber()) return value.asLong();
        }
        return null;
    }

    /** 合并流中多次 usage 事件，避免供应商分批返回统计时丢字段。 */
    private TokenUsage mergeUsage(TokenUsage previous, JsonNode event) {
        String model = event.path("model").isTextual() ? event.path("model").asText() : previous == null ? null : previous.model();
        Long input = first(token(event, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens"), previous == null ? null : previous.inputTokens());
        Long output = first(token(event, "outputTokens", "output_tokens", "completionTokens", "completion_tokens"), previous == null ? null : previous.outputTokens());
        Long total = first(token(event, "totalTokens", "total_tokens"), previous == null ? null : previous.totalTokens());
        if (total == null && (input != null || output != null)) total = (input == null ? 0 : input) + (output == null ? 0 : output);
        return new TokenUsage(model, input, output, total);
    }

    /** 选择最新的非空 Token 值。 */
    private Long first(Long current, Long previous) { return current == null ? previous : current; }

    /** 流式调用已收到的模型和 Token 统计。 */
    private record TokenUsage(String model, Long inputTokens, Long outputTokens, Long totalTokens) {}

    /** 将客户端断开转换为流式失败，避免泄漏下游异常文本。 */
    private void emit(EventConsumer consumer, JsonNode event) {
        try { consumer.accept(event); }
        catch (IOException exception) { throw new IllegalStateException("Chat stream disconnected", exception); }
    }

    /** 首个增量已经交给调用方后禁止重试，避免上游副作用和重复回答。 */
    private static final class StreamAlreadyStartedException extends IllegalStateException {
        private StreamAlreadyStartedException(Throwable cause) { super("Stream already started", cause); }
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
