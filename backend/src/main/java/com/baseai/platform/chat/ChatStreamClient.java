package com.baseai.platform.chat;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.service.LlmManagementService;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.trace.TraceContextHolder;
import com.baseai.platform.workflow.WorkflowAgentClient;
import com.baseai.platform.workflow.WorkflowExecutionService;
import com.baseai.platform.workflow.WorkflowModels;
import com.baseai.platform.workflow.WorkflowService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final WorkflowAgentClient agentClient;
    private final WorkflowService workflowService;
    private final WorkflowExecutionService workflowExecutionService;

    /** 为流式接口设置连接及响应头超时，并注入聊天 Agent 的工作流执行能力。 */
    @Autowired
    public ChatStreamClient(@Qualifier("pythonWorkerRestClient") RestClient client, LlmManagementService management,
                            TaskTraceService traces, ObjectMapper mapper, Retry pythonWorkerRetry, 
                            CircuitBreaker pythonWorkerCircuitBreaker, WorkflowAgentClient agentClient,
                            WorkflowService workflowService, WorkflowExecutionService workflowExecutionService) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = client.mutate().requestFactory(factory).build();
        this.management = management; this.traces = traces; this.mapper = mapper;
        this.retry = pythonWorkerRetry;
        this.circuitBreaker = pythonWorkerCircuitBreaker;
        this.agentClient = agentClient;
        this.workflowService = workflowService;
        this.workflowExecutionService = workflowExecutionService;
    }

    /** 保留基础流测试使用的构造方式，普通聊天不需要工作流依赖。 */
    public ChatStreamClient(@Qualifier("pythonWorkerRestClient") RestClient client, LlmManagementService management,
                            TaskTraceService traces, ObjectMapper mapper, Retry pythonWorkerRetry,
                            CircuitBreaker pythonWorkerCircuitBreaker) {
        this(client, management, traces, mapper, pythonWorkerRetry, pythonWorkerCircuitBreaker,
            null, null, null);
    }

    /** 逐事件读取；消费者返回前不会预取下一事件。使用重试和熔断保护。 */
    public void stream(ChatConversationService.Turn turn, EventConsumer consumer) {
        if (turn.settings().toolCodes() != null && !turn.settings().toolCodes().isEmpty()) {
            streamAgent(turn, consumer);
            return;
        }
        AtomicBoolean streamStarted = new AtomicBoolean(false);
        Supplier<Void> supplier = () -> {
            executeStream(turn, event -> {
                if ("delta".equals(event.path("type").asText())) streamStarted.set(true);
                consumer.accept(event);
            }, streamStarted);
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
    private void executeStream(ChatConversationService.Turn turn, EventConsumer consumer, AtomicBoolean streamStarted) {
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
            if (streamStarted.get()) throw new StreamAlreadyStartedException(exception);
            throw exception;
        }
    }

    /** 让模型在授权工作流工具中循环决策，并把工具状态作为 SSE 事件返回。 */
    private void streamAgent(ChatConversationService.Turn turn, EventConsumer consumer) {
        if (agentClient == null || workflowService == null || workflowExecutionService == null) {
            throw new BusinessException("chat.agentUnavailable");
        }
        Map<String, WorkflowModels.ChatToolOption> options = new LinkedHashMap<>();
        workflowService.chatToolOptions().forEach(option -> options.put(option.code(), option));
        List<WorkflowAgentClient.Tool> tools = new ArrayList<>();
        for (String code : turn.settings().toolCodes()) {
            WorkflowModels.ChatToolOption option = options.get(code);
            if (option == null) throw new BusinessException("workflow.agentToolForbidden", code);
            tools.add(new WorkflowAgentClient.Tool(option.code(), option.description(), option.inputSchema()));
        }
        if (tools.isEmpty()) throw new BusinessException("workflow.agentToolsRequired");

        var config = mapper.createObjectNode()
            .put("featureCode", turn.settings().featureCode() == null ? "chat" : turn.settings().featureCode())
            .put("modelType", turn.settings().modelType())
            .put("enableThinking", Boolean.TRUE.equals(turn.settings().enableThinking()));
        if (turn.settings().modelId() != null) config.put("modelId", turn.settings().modelId());
        if (turn.settings().thinkingLevel() != null) config.put("thinkingLevel", turn.settings().thinkingLevel());

        List<Map<String, Object>> messages = new ArrayList<>();
        for (AiChatClient.Message message : turn.context()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content());
            messages.add(item);
        }
        List<Map<String, Object>> toolEvents = new ArrayList<>();
        String model = "";
        for (int step = 0; step < 5; step++) {
            TraceContextHolder.checkpoint();
            WorkflowAgentClient.AgentStep decision = agentClient.step(config, messages, tools);
            model = decision.model();
            if (decision.toolCalls().isEmpty()) {
                if (decision.content().isBlank()) throw new BusinessException("ai.emptyResponse");
                emit(consumer, mapper.createObjectNode().put("type", "delta").put("content", decision.content()));
                var done = mapper.createObjectNode().put("type", "done").put("model", model);
                done.set("toolCalls", mapper.valueToTree(toolEvents));
                emit(consumer, done);
                return;
            }

            List<Map<String, Object>> rawCalls = new ArrayList<>();
            for (WorkflowAgentClient.ToolCall call : decision.toolCalls()) {
                rawCalls.add(Map.of("id", call.id(), "type", "function", "function",
                    Map.of("name", call.name(), "arguments", call.arguments().toString())));
            }
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", decision.content());
            assistant.put("tool_calls", rawCalls);
            messages.add(assistant);

            for (WorkflowAgentClient.ToolCall call : decision.toolCalls()) {
                WorkflowModels.ChatToolOption option = options.get(call.name());
                if (option == null) throw new BusinessException("workflow.agentToolForbidden", call.name());
                emit(consumer, mapper.createObjectNode().put("type", "agent_tool_start")
                    .put("toolCallId", call.id()).put("toolName", option.name()).put("toolCode", option.code()));
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("toolCallId", call.id());
                event.put("toolName", option.name());
                event.put("toolCode", option.code());
                try {
                    Map<String, Object> arguments = mapper.convertValue(call.arguments(), Map.class);
                    WorkflowModels.RunAccepted accepted = workflowExecutionService.startPublished(option.code(), arguments);
                    JsonNode result = awaitTool(accepted.runId());
                    String resultText = mapper.writeValueAsString(result == null ? mapper.nullNode() : result);
                    if (resultText.length() > 100_000) resultText = resultText.substring(0, 100_000) + "…";
                    messages.add(new LinkedHashMap<>(Map.of("role", "tool", "tool_call_id", call.id(),
                        "name", call.name(), "content", resultText)));
                    event.put("success", true);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    event.put("success", false);
                    event.put("error", "interrupted");
                    emit(consumer, toolDone(call, option, false));
                    throw new BusinessException("chat.agentToolInterrupted");
                } catch (RuntimeException | IOException exception) {
                    event.put("success", false);
                    event.put("error", "failed");
                    emit(consumer, toolDone(call, option, false));
                    throw new BusinessException("chat.agentToolFailed");
                }
                toolEvents.add(event);
                emit(consumer, toolDone(call, option, true));
            }
        }
        throw new BusinessException("chat.agentStepLimit");
    }

    /** 轮询已持久化的工作流运行，确保聊天 Agent 获得真实工具结果。 */
    private JsonNode awaitTool(String runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            TraceContextHolder.checkpoint();
            WorkflowModels.RunView run = workflowExecutionService.run(runId);
            if ("SUCCESS".equals(run.status())) return run.output();
            if (Set.of("FAILED", "CANCELLED").contains(run.status())) throw new BusinessException("chat.agentToolFailed");
            Thread.sleep(200L);
        }
        try { workflowExecutionService.cancel(runId); } catch (RuntimeException ignored) { }
        throw new BusinessException("chat.agentToolTimeout");
    }

    /** 构造不包含工具结果正文的状态事件，避免把业务数据写入前端日志。 */
    private JsonNode toolDone(WorkflowAgentClient.ToolCall call, WorkflowModels.ChatToolOption option,
                              boolean success) {
        return mapper.createObjectNode().put("type", "agent_tool_done")
            .put("toolCallId", call.id()).put("toolName", option.name())
            .put("toolCode", option.code()).put("success", success);
    }

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
