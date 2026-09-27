package com.baseai.platform.chat;

import com.baseai.platform.automation.ApiTriggerModels;
import com.baseai.platform.automation.ApiTriggerService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.service.LlmManagementService;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.service.LlmUsageService;
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
    private final WorkflowAgentClient agentClient;
    private final WorkflowService workflowService;
    private final WorkflowExecutionService workflowExecutionService;
    private final ApiTriggerService apiTriggerService;
    private final LlmUsageService llmUsageService;

    /** 为流式接口设置连接及响应头超时，并注入聊天 Agent 的工作流执行能力。 */
    @Autowired
    public ChatStreamClient(@Qualifier("pythonWorkerRestClient") RestClient client, LlmManagementService management,
                            TaskTraceService traces, ObjectMapper mapper, Retry pythonWorkerRetry, 
                            CircuitBreaker pythonWorkerCircuitBreaker, WorkflowAgentClient agentClient,
                            WorkflowService workflowService, WorkflowExecutionService workflowExecutionService,
                            ApiTriggerService apiTriggerService, LlmUsageService llmUsageService) {
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
        this.apiTriggerService = apiTriggerService;
        this.llmUsageService = llmUsageService;
    }

    /** 保留基础流测试使用的构造方式，普通聊天不需要工作流依赖。 */
    public ChatStreamClient(@Qualifier("pythonWorkerRestClient") RestClient client, LlmManagementService management,
                            TaskTraceService traces, ObjectMapper mapper, Retry pythonWorkerRetry,
                            CircuitBreaker pythonWorkerCircuitBreaker) {
        this(client, management, traces, mapper, pythonWorkerRetry, pythonWorkerCircuitBreaker,
            null, null, null, null, null);
    }

    /** 兼容已有仅注入工作流 Agent 依赖的测试构造方式。 */
    public ChatStreamClient(@Qualifier("pythonWorkerRestClient") RestClient client, LlmManagementService management,
                            TaskTraceService traces, ObjectMapper mapper, Retry pythonWorkerRetry,
                            CircuitBreaker pythonWorkerCircuitBreaker, WorkflowAgentClient agentClient,
                            WorkflowService workflowService, WorkflowExecutionService workflowExecutionService) {
        this(client, management, traces, mapper, pythonWorkerRetry, pythonWorkerCircuitBreaker,
            agentClient, workflowService, workflowExecutionService, null, null);
    }

    /** 兼容已有包含 API 触发器依赖的测试构造方式。 */
    public ChatStreamClient(@Qualifier("pythonWorkerRestClient") RestClient client, LlmManagementService management,
                            TaskTraceService traces, ObjectMapper mapper, Retry pythonWorkerRetry,
                            CircuitBreaker pythonWorkerCircuitBreaker, WorkflowAgentClient agentClient,
                            WorkflowService workflowService, WorkflowExecutionService workflowExecutionService,
                            ApiTriggerService apiTriggerService) {
        this(client, management, traces, mapper, pythonWorkerRetry, pythonWorkerCircuitBreaker,
            agentClient, workflowService, workflowExecutionService, apiTriggerService, null);
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
        if (!turn.settings().effectiveAgentToolCodes().isEmpty()
            || !turn.settings().effectiveWorkflowToolCodes().isEmpty()) {
            streamAgent(turn, consumer);
            return;
        }
        // 路由配置错误不能作为 Worker 故障重试或计入熔断；本轮重试固定使用同一快照。
        var settings = turn.settings();
        String feature = settings.featureCode() == null || settings.featureCode().isBlank() ? "chat" : settings.featureCode();
        var route = settings.modelId() == null ? management.resolveActive(feature, settings.modelType()) :
            management.resolveModel(settings.modelId(), settings.modelType(), Boolean.TRUE.equals(settings.enableThinking()), settings.thinkingLevel());
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
                                llmUsageService.markFirstToken(usageId);
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
        if (route == null || model == null) return null;
        return route.candidates().stream().filter(candidate -> model.equals(candidate.model()))
            .map(LlmManagementService.WorkerCandidate::providerCode).findFirst().orElse(null);
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

    /** 让模型在独立 Agent 工具和显式工作流工具中循环决策，并把状态作为 SSE 事件返回。 */
    private void streamAgent(ChatConversationService.Turn turn, EventConsumer consumer) {
        List<String> agentCodes = turn.settings().effectiveAgentToolCodes();
        List<String> workflowCodes = turn.settings().effectiveWorkflowToolCodes();
        if (agentClient == null
            || !agentCodes.isEmpty() && apiTriggerService == null
            || !workflowCodes.isEmpty() && (workflowService == null || workflowExecutionService == null)) {
            throw new BusinessException("chat.agentUnavailable");
        }
        Map<String, SelectedTool> availableOptions = new LinkedHashMap<>();
        if (!agentCodes.isEmpty()) apiTriggerService.agentToolOptions(turn.owner()).forEach(option ->
            availableOptions.put(option.code(), new SelectedTool(option.code(), option.name(), option.description(),
                option.inputSchema(), true)));
        if (!workflowCodes.isEmpty()) workflowService.chatToolOptions().forEach(option ->
            availableOptions.put(option.code(), new SelectedTool(option.code(), option.name(), option.description(),
                option.inputSchema(), false)));
        // 模型返回值不可信：执行目录只包含本轮显式选择并通过目录校验的工具。
        Map<String, SelectedTool> options = new LinkedHashMap<>();
        List<WorkflowAgentClient.Tool> tools = new ArrayList<>();
        for (String code : concat(agentCodes, workflowCodes)) {
            SelectedTool option = availableOptions.get(code);
            if (option == null) throw new BusinessException("workflow.agentToolForbidden", code);
            options.put(code, option);
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
                SelectedTool option = options.get(call.name());
                if (option == null) throw new BusinessException("workflow.agentToolForbidden", call.name());
                emit(consumer, mapper.createObjectNode().put("type", "agent_tool_start")
                    .put("toolCallId", call.id()).put("toolName", option.name()).put("toolCode", option.code()));
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("toolCallId", call.id());
                event.put("toolName", option.name());
                event.put("toolCode", option.code());
                try {
                    Map<String, Object> arguments = mapper.convertValue(call.arguments(), Map.class);
                    JsonNode result;
                    if (option.agentTool()) {
                        ApiTriggerModels.ExecutionResult execution = apiTriggerService.executeAgentTool(
                            turn.owner(), option.code(), call.arguments());
                        result = mapper.createObjectNode()
                            .put("httpStatus", execution.httpStatus())
                            .put("durationMs", execution.durationMs())
                            .put("responseBody", execution.responseBody());
                    } else {
                        WorkflowModels.RunAccepted accepted = workflowExecutionService.startPublished(option.code(), arguments);
                        result = awaitTool(accepted.runId());
                    }
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
    private JsonNode toolDone(WorkflowAgentClient.ToolCall call, SelectedTool option,
                              boolean success) {
        return mapper.createObjectNode().put("type", "agent_tool_done")
            .put("toolCallId", call.id()).put("toolName", option.name())
            .put("toolCode", option.code()).put("success", success);
    }

    /** 拼接两个独立工具来源，保持模型收到的工具顺序稳定。 */
    private List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first == null ? List.of() : first);
        if (second != null) result.addAll(second);
        return result;
    }

    /** 统一封装模型可见工具，标记执行器是否进入独立 Agent 工具分支。 */
    private record SelectedTool(String code, String name, String description, JsonNode inputSchema,
                                boolean agentTool) {}

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
