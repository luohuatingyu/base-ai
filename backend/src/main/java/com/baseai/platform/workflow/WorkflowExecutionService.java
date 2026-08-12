package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerModels;
import com.baseai.platform.automation.ApiTriggerService;
import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.service.AiChatClient;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.trace.TraceCancelledException;
import com.baseai.platform.trace.TraceContext;
import com.baseai.platform.trace.TraceContextHolder;
import com.baseai.platform.trace.TraceRuntime;
import com.baseai.platform.trace.TraceRuntimeRegistry;
import com.baseai.platform.trace.TraceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.core.task.TaskRejectedException;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** 异步执行不可变工作流版本，并持久化工作流与节点级状态。 */
@Service
public class WorkflowExecutionService implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final WorkflowService workflowService;
    private final WorkflowExpressionService expressions;
    private final WorkflowAgentClient agentClient;
    private final AiChatClient aiChatClient;
    private final ApiTriggerService apiTriggerService;
    private final WorkflowNodeExecutorRegistry nodeExecutors;
    private final WorkflowAccessService accessService;
    private final ThreadPoolTaskExecutor executor;
    private final TaskTraceService taskTraceService;
    private final TraceRuntimeRegistry runtimeRegistry;
    private final WorkflowErrorMessageService errorMessages;
    private final PlatformProperties.Workflow limits;
    private final String instanceId;
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ThreadLocal<String> budgetRunId = new ThreadLocal<>();

    /** 注入执行所需的持久化、节点适配器、线程池和追踪组件。 */
    public WorkflowExecutionService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper, ConfigCryptoService cryptoService,
                                    WorkflowService workflowService, WorkflowExpressionService expressions,
                                    WorkflowAgentClient agentClient, AiChatClient aiChatClient,
                                    ApiTriggerService apiTriggerService,
                                    WorkflowNodeExecutorRegistry nodeExecutors, WorkflowAccessService accessService,
                                    @Qualifier("workflowTaskExecutor") ThreadPoolTaskExecutor executor,
                                    TaskTraceService taskTraceService, TraceRuntimeRegistry runtimeRegistry,
                                    WorkflowErrorMessageService errorMessages,
                                    PlatformProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.workflowService = workflowService;
        this.expressions = expressions;
        this.agentClient = agentClient;
        this.aiChatClient = aiChatClient;
        this.apiTriggerService = apiTriggerService;
        this.nodeExecutors = nodeExecutors;
        this.accessService = accessService;
        this.executor = executor;
        this.taskTraceService = taskTraceService;
        this.runtimeRegistry = runtimeRegistry;
        this.errorMessages = errorMessages;
        this.limits = properties.getWorkflow();
        this.instanceId = properties.getPythonWorker().getJavaInstanceId() + ":" + UUID.randomUUID();
    }

    /** 启动时只回收租约已经过期的遗留运行，不影响其他实例的活跃任务。 */
    @Override
    public void run(ApplicationArguments arguments) {
        recoverExpiredLeases();
    }

    /** 从画布启动当前草稿版本。 */
    public WorkflowModels.RunAccepted startDraft(Long workflowId, Map<String, Object> inputs) {
        WorkflowModels.WorkflowView workflow = workflowService.workflow(workflowId);
        WorkflowModels.StoredVersion version = workflowService.storedVersion(workflow.currentVersionId());
        return enqueue(version, inputs, "MANUAL", AuthContext.require().id(), currentApiKeyId(), null, 0, Set.of());
    }

    /** 从开放平台按稳定编码启动已发布版本。 */
    public WorkflowModels.RunAccepted startPublished(String workflowCode, Map<String, Object> inputs) {
        WorkflowModels.StoredVersion version = workflowService.executable(workflowCode, true);
        return enqueue(version, inputs, "API", AuthContext.require().id(), currentApiKeyId(), null, 0, Set.of());
    }

    /** 由平台原生触发器按已发布版本所有者启动工作流。 */
    public WorkflowModels.RunAccepted startTriggered(String workflowCode, Map<String, Object> inputs, String triggerType) {
        WorkflowModels.StoredVersion version = workflowService.executableInternal(workflowCode, true);
        return enqueue(version, inputs, triggerType, version.workflowOwnerId(), null, null, 0, Set.of());
    }

    /** 创建异步运行记录、MySQL 任务追踪和可取消 Future。 */
    private WorkflowModels.RunAccepted enqueue(WorkflowModels.StoredVersion version, Map<String, Object> rawInputs,
                                                String triggerType, Long ownerId, Long apiKeyId, String parentRunId,
                                                int depth, Set<String> stack) {
        workflowService.validateExecutableConfiguration(version);
        ObjectNode inputs = objectMapper.valueToTree(rawInputs == null ? Map.of() : rawInputs);
        validateInputs(version.inputSchema(), inputs);
        enforcePayload(inputs);
        String runId = UUID.randomUUID().toString();
        String traceId = taskTraceService.create(null, ownerId, "WORKFLOW_EXECUTE", triggerType, "POST",
            "/api/workflows/" + version.workflowCode() + "/runs", new TraceSnapshot("{}", "{}"));
        try { insertRun(runId, version, parentRunId, traceId, triggerType, inputs, ownerId, apiKeyId); }
        catch (RuntimeException exception) {
            taskTraceService.markFailed(traceId, "工作流运行记录创建失败"); throw exception;
        }
        TraceRuntime runtime = runtimeRegistry.create(traceId);
        try {
            Future<?> future = executor.submit(() -> executeQueued(runId, version, inputs, ownerId, triggerType, depth, stack, runtime));
            runtime.registerFuture(future);
            futures.put(runId, future);
            if (future.isDone()) futures.remove(runId, future);
        } catch (TaskRejectedException exception) {
            String message = errorMessages.encode("workflow.queueFull");
            jdbcTemplate.update("""
                UPDATE workflow_run SET status='FAILED',error_message=?,finished_at=NOW(),lease_expires_at=NULL,updated_at=NOW()
                WHERE id=? AND status='QUEUED'
                """, message, runId);
            taskTraceService.markFailed(traceId, readableError(message)); runtimeRegistry.remove(traceId);
            throw new BusinessException(503, "workflow.queueFull");
        }
        return new WorkflowModels.RunAccepted(runId, "QUEUED");
    }

    /** 在线程池中绑定追踪上下文并维护统一终态。 */
    private void executeQueued(String runId, WorkflowModels.StoredVersion version, ObjectNode inputs, Long ownerId,
                               String triggerType, int depth, Set<String> stack, TraceRuntime runtime) {
        String traceId = traceId(runId);
        budgetRunId.set(runId);
        runtime.registerThread(Thread.currentThread());
        TraceContext context = new TraceContext(traceId, ownerId, "WORKFLOW_EXECUTE", triggerType, runtime.token(), runtime);
        try (TraceContextHolder.Scope ignored = TraceContextHolder.bind(context)) {
            if (!markRunRunning(runId)) throw new TraceCancelledException(traceId);
            JsonNode output = executeGraph(runId, version, inputs, depth, stack, new AtomicInteger());
            enforcePayload(output);
            if (!completeRunSuccess(runId, output)) throw new TraceCancelledException(traceId);
            taskTraceService.markSuccess(traceId);
        } catch (WorkflowWaitSignal signal) {
            try {
                persistWait(runId, signal.checkpoint());
                taskTraceService.markWaiting(traceId);
            } catch (TraceCancelledException exception) {
                markCancelled(runId); taskTraceService.completeCancellation(traceId);
            }
        } catch (TraceCancelledException exception) {
            markCancelled(runId);
            taskTraceService.completeCancellation(traceId);
        } catch (Throwable exception) {
            if (runtime.token().isCancelled() || Thread.currentThread().isInterrupted()) {
                markCancelled(runId);
                taskTraceService.completeCancellation(traceId);
            } else {
                String message = errorMessages.encode(exception);
                int failed = jdbcTemplate.update("""
                    UPDATE workflow_run SET status='FAILED',error_message=?,finished_at=NOW(),lease_expires_at=NULL,updated_at=NOW()
                    WHERE id=? AND status IN ('QUEUED','RUNNING') AND cancel_requested=false
                    """, message, runId);
                if (failed == 1) taskTraceService.markFailed(traceId, readableError(message));
                else { markCancelled(runId); taskTraceService.completeCancellation(traceId); }
            }
        } finally {
            futures.remove(runId);
            runtime.unregisterThread(Thread.currentThread());
            runtimeRegistry.remove(traceId);
            budgetRunId.remove();
            Thread.interrupted();
        }
    }

    /** 按拓扑顺序执行单层画布，条件分支只激活命中连线。 */
    private JsonNode executeGraph(String runId, WorkflowModels.StoredVersion version, ObjectNode inputs,
                                  int depth, Set<String> stack, AtomicInteger sequence) {
        if (depth > limits.getMaxRecursionDepth() || stack.contains(version.workflowCode())) {
            throw new BusinessException("workflow.recursionLimit");
        }
        Set<String> nextStack = new LinkedHashSet<>(stack);
        nextStack.add(version.workflowCode());
        ObjectNode context = objectMapper.createObjectNode();
        context.set("input", inputs);
        context.set("nodes", objectMapper.createObjectNode());
        context.set("loop", objectMapper.createObjectNode());
        return executeGraphNodes(runId, version.graph(), version.templateSnapshots(), context, depth, nextStack, sequence, "",
            version.workflowOwnerId(), null);
    }

    /** 执行主图或循环子图中的节点，并返回结束节点输出。 */
    private JsonNode executeGraphNodes(String runId, JsonNode graph, JsonNode snapshots, ObjectNode context,
                                       int depth, Set<String> stack, AtomicInteger sequence, String iterationPath,
                                       Long workflowOwnerId, WaitCheckpoint checkpoint) {
        if (depth > limits.getMaxRecursionDepth()) throw new BusinessException("workflow.recursionLimit");
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        graph.path("nodes").forEach(node -> nodes.put(node.path("id").asText(), node));
        List<JsonNode> edges = new ArrayList<>();
        graph.path("edges").forEach(edges::add);
        List<String> order = topologicalOrder(nodes, edges);
        Set<String> selectedEdges = checkpoint == null ? new HashSet<>() : new HashSet<>(checkpoint.selectedEdges());
        JsonNode finalOutput = checkpoint == null ? objectMapper.createObjectNode() : checkpoint.finalOutput().deepCopy();
        int startIndex = checkpoint == null ? 0 : checkpoint.nextIndex();
        if (checkpoint != null) {
            context.removeAll(); context.setAll(checkpoint.context()); sequence.set(Math.max(sequence.get(), checkpoint.sequence()));
            JsonNode waitedNode = nodes.get(checkpoint.waitNodeId());
            ObjectNode waitedOutput = objectMapper.createObjectNode().put("waitedMilliseconds", checkpoint.waitedMilliseconds())
                .put("resumedAt", Instant.now().toString());
            ((ObjectNode) context.path("nodes")).set(checkpoint.waitNodeId(), waitedOutput);
            finishNodeRun(checkpoint.nodeRunId(), "SUCCESS", waitedOutput, null);
            for (JsonNode edge : edges.stream().filter(item -> checkpoint.waitNodeId().equals(item.path("source").asText())).toList()) {
                selectedEdges.add(edge.path("id").asText());
            }
        }
        for (int orderIndex = startIndex; orderIndex < order.size(); orderIndex++) {
            String nodeId = order.get(orderIndex);
            TraceContextHolder.checkpoint();
            JsonNode node = nodes.get(nodeId);
            String type = WorkflowGraphValidator.nodeType(node);
            List<JsonNode> incoming = edges.stream().filter(edge -> nodeId.equals(edge.path("target").asText())).toList();
            boolean entry = "START".equals(type) || WorkflowNodeTypes.TRIGGERS.contains(type);
            if (!entry && incoming.stream().noneMatch(edge -> selectedEdges.contains(edge.path("id").asText()))) continue;
            ObjectNode config = nodeConfig(node, snapshots.path(nodeId));
            enforcePayload(context);
            JsonNode nodeInput = objectMapper.createObjectNode().set("context", context.deepCopy());
            int step = sequence.incrementAndGet();
            if (step > Math.max(1, limits.getMaxExecutionSteps())) throw new BusinessException("workflow.executionStepLimit", limits.getMaxExecutionSteps());
            long nodeRunId = startNodeRun(runId, node, type, step, iterationPath, nodeInput);
            try {
                if ("WAIT".equals(type)) {
                    if (depth > 0) throw new BusinessException("workflow.waitNestedForbidden");
                    long milliseconds = waitMilliseconds(config, context);
                    markNodeWaiting(nodeRunId);
                    throw new WorkflowWaitSignal(new WaitCheckpoint(context.deepCopy(), new ArrayList<>(selectedEdges),
                        orderIndex + 1, finalOutput.deepCopy(), sequence.get(), nodeId, nodeRunId, milliseconds));
                }
                NodeResult result = executeNodeWithPolicy(runId, node, type, config, context, depth, stack, sequence,
                    iterationPath, workflowOwnerId);
                enforcePayload(result.output());
                ((ObjectNode) context.path("nodes")).set(nodeId, result.output());
                finishNodeRun(nodeRunId, result.error() == null ? "SUCCESS" : "FAILED_CONTINUED", result.output(), result.error());
                if ("END".equals(type)) finalOutput = result.output();
                for (JsonNode edge : edges.stream().filter(item -> nodeId.equals(item.path("source").asText())).toList()) {
                    String handle = edge.path("sourceHandle").asText("").toLowerCase(Locale.ROOT);
                    if (result.branch() == null || handle.isBlank() || handle.equals(result.branch().toLowerCase(Locale.ROOT))) {
                        selectedEdges.add(edge.path("id").asText());
                    }
                }
            } catch (WorkflowWaitSignal signal) { throw signal; }
            catch (RuntimeException exception) {
                finishNodeRun(nodeRunId, "FAILED", null, errorMessages.encode(exception));
                throw exception;
            }
        }
        return finalOutput;
    }

    /** 按节点通用重试和失败策略执行一次节点。 */
    private NodeResult executeNodeWithPolicy(String runId, JsonNode node, String type, ObjectNode config, ObjectNode context,
                                             int depth, Set<String> stack, AtomicInteger sequence, String iterationPath,
                                             Long workflowOwnerId) {
        int attempts = Math.max(1, Math.min(config.path("maxAttempts").asInt(1), 5));
        long delay = Math.max(0, Math.min(config.path("retryDelayMillis").asLong(0), 30_000));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                NodeResult result = executeNode(runId, node, type, config, context, depth, stack, sequence, iterationPath, workflowOwnerId);
                if (result.branch() == null && "BRANCH".equalsIgnoreCase(config.path("onError").asText())) {
                    return new NodeResult(result.output(), "success", result.error());
                }
                return result;
            } catch (TraceCancelledException exception) { throw exception; }
            catch (RuntimeException exception) {
                last = exception;
                if (attempt < attempts) cancellableDelay(delay * attempt);
            }
        }
        String message = last == null ? errorMessages.encode("workflow.nodeExecutionFailed") : errorMessages.encode(last);
        String strategy = config.path("onError").asText("FAIL").toUpperCase(Locale.ROOT);
        if ("CONTINUE".equals(strategy) || "BRANCH".equals(strategy)) {
            ObjectNode output = objectMapper.createObjectNode().put("failed", true).put("error", readableError(message));
            return new NodeResult(output, "BRANCH".equals(strategy) ? "error" : null, message);
        }
        throw last == null ? new BusinessException("workflow.nodeExecutionFailed") : last;
    }

    /** 以短间隔执行可取消退避等待。 */
    private void cancellableDelay(long milliseconds) {
        long remaining = milliseconds;
        while (remaining > 0) {
            TraceContextHolder.checkpoint();
            long chunk = Math.min(remaining, 250L);
            try { Thread.sleep(chunk); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new TraceCancelledException(TraceContextHolder.currentTraceId().orElse(""));
            }
            remaining -= chunk;
        }
    }

    /** 分派单个节点类型并生成节点输出及可选条件分支。 */
    private NodeResult executeNode(String runId, JsonNode node, String type, ObjectNode config, ObjectNode context,
                                   int depth, Set<String> stack, AtomicInteger sequence, String iterationPath,
                                   Long workflowOwnerId) {
        return switch (type) {
            case "START" -> new NodeResult(context.path("input").deepCopy(), null, null);
            case "WEBHOOK_TRIGGER", "SCHEDULE_TRIGGER", "KAFKA_TRIGGER", "RABBITMQ_TRIGGER" ->
                new NodeResult(context.path("input").deepCopy(), null, null);
            case "END" -> new NodeResult(config.has("output") ? expressions.resolve(config.path("output"), context)
                : context.path("nodes").deepCopy(), null, null);
            case "LLM" -> new NodeResult(executeLlm(config, context), null, null);
            case "HTTP" -> new NodeResult(executeHttp(config, context), null, null);
            case "CONDITION" -> {
                boolean matched = expressions.condition(config.path("condition"), context);
                yield new NodeResult(objectMapper.createObjectNode().put("matched", matched), String.valueOf(matched), null);
            }
            case "ITERATION" -> new NodeResult(executeIteration(runId, config, context, depth, stack, sequence, iterationPath,
                workflowOwnerId), null, null);
            case "LOOP" -> new NodeResult(executeLoop(runId, config, context, depth, stack, sequence, iterationPath,
                workflowOwnerId), null, null);
            case "AGENT" -> new NodeResult(executeAgent(runId, config, context, depth, stack, sequence, workflowOwnerId), null, null);
            case "SUB_WORKFLOW" -> new NodeResult(executeSubWorkflow(runId, config, context, depth, stack, sequence,
                workflowOwnerId), null, null);
            case "WAIT" -> throw new BusinessException("workflow.waitNestedForbidden");
            default -> {
                if (!nodeExecutors.supports(type)) throw new BusinessException("workflow.nodeTypeInvalid");
                WorkflowNodeExecutor.Result result = nodeExecutors.require(type).execute(new WorkflowNodeExecutor.Request(
                    runId, node.path("id").asText(), type, config, context.deepCopy(), workflowOwnerId));
                yield new NodeResult(result.output(), result.branch(), null);
            }
        };
    }

    /** 调用现有模型路由执行 LLM 节点。 */
    private JsonNode executeLlm(ObjectNode config, ObjectNode context) {
        JsonNode resolved = expressions.resolve(config, context);
        WorkflowNodeConfigValidator.validateResolved("LLM", resolved);
        List<AiChatClient.Message> messages = new ArrayList<>();
        String systemPrompt = resolved.path("systemPrompt").asText("");
        if (!systemPrompt.isBlank()) messages.add(new AiChatClient.Message("system", systemPrompt));
        String prompt = resolved.path("prompt").asText();
        messages.add(new AiChatClient.Message("user", prompt));
        Long modelId = resolved.hasNonNull("modelId") ? resolved.path("modelId").asLong() : null;
        String modelType = resolved.hasNonNull("modelType") ? resolved.path("modelType").asText() : modelId == null ? "text_model" : "";
        AiChatClient.ChatResult result = aiChatClient.chat(resolved.path("featureCode").asText("DEFAULT"),
            modelType, messages, resolved.path("temperature").asDouble(0),
            resolved.has("enableThinking") ? resolved.path("enableThinking").asBoolean() : null,
            resolved.path("thinkingLevel").asText(null), modelId);
        return objectMapper.createObjectNode().put("content", result.content()).put("model", result.model())
            .put("inputTokens", result.inputTokens()).put("outputTokens", result.outputTokens()).put("totalTokens", result.totalTokens());
    }

    /** 将节点配置映射为接口触发命令，复用已有 SSRF 和 TLS 安全实现。 */
    private JsonNode executeHttp(ObjectNode config, ObjectNode context) {
        JsonNode resolved = expressions.resolve(WorkflowNodeConfigDefaults.withDefaults(objectMapper, "HTTP", config), context);
        WorkflowNodeConfigValidator.validateResolved("HTTP", resolved);
        ApiTriggerModels.Command command = new ApiTriggerModels.Command(
            resolved.path("name").asText("Workflow HTTP"), "Workflow node", resolved.path("method").asText("GET"),
            resolved.path("url").asText(), jsonText(resolved.path("headers")), jsonText(resolved.path("queryParams")),
            bodyText(resolved.path("body")), resolved.path("contentType").asText("application/json"), null,
            resolved.path("timeoutSeconds").asInt(30), true, false, "", "POST", "", "application/json",
            "data.token", "Authorization", "Bearer ");
        ApiTriggerModels.ExecutionResult result = apiTriggerService.test(command);
        ObjectNode output = objectMapper.createObjectNode().put("httpStatus", result.httpStatus())
            .put("durationMs", result.durationMs()).put("body", result.responseBody());
        try { output.set("json", objectMapper.readTree(result.responseBody())); } catch (Exception ignored) { }
        return output;
    }

    /** 顺序遍历数组并对每个元素执行受控子画布。 */
    private JsonNode executeIteration(String runId, ObjectNode config, ObjectNode context, int depth, Set<String> stack,
                                      AtomicInteger sequence, String parentPath, Long workflowOwnerId) {
        JsonNode collection = expressions.resolve(config.path("collection"), context);
        if (!collection.isArray()) throw new BusinessException("workflow.iterationInputInvalid");
        int maximum = bounded(config.path("maxIterations").asInt(limits.getMaxIterations()), limits.getMaxIterations());
        if (collection.size() > maximum) throw new BusinessException("workflow.iterationLimit", maximum);
        JsonNode body = config.path("bodyGraph");
        ArrayNode outputs = objectMapper.createArrayNode();
        for (int index = 0; index < collection.size(); index++) {
            TraceContextHolder.checkpoint();
            ObjectNode child = context.deepCopy();
            ObjectNode loop = objectMapper.createObjectNode().put("index", index);
            loop.set("item", collection.get(index));
            child.set("loop", loop);
            String path = parentPath.isBlank() ? String.valueOf(index) : parentPath + "/" + index;
            outputs.add(executeGraphNodes(runId, body, objectMapper.createObjectNode(), child, depth + 1, stack, sequence, path,
                workflowOwnerId, null));
        }
        return objectMapper.createObjectNode().put("iterations", collection.size()).set("items", outputs);
    }

    /** 在退出条件或硬上限触发前重复执行子画布。 */
    private JsonNode executeLoop(String runId, ObjectNode config, ObjectNode context, int depth, Set<String> stack,
                                 AtomicInteger sequence, String parentPath, Long workflowOwnerId) {
        int maximum = bounded(config.path("maxIterations").asInt(10), limits.getMaxIterations());
        JsonNode body = config.path("bodyGraph");
        ArrayNode outputs = objectMapper.createArrayNode();
        JsonNode last = objectMapper.nullNode();
        int count = 0;
        ((ObjectNode) context.path("loop")).put("index", 0).set("lastOutput", last);
        while (expressions.condition(config.path("condition"), context)) {
            if (count >= maximum) throw new BusinessException("workflow.loopLimit", maximum);
            TraceContextHolder.checkpoint();
            ObjectNode child = context.deepCopy();
            ObjectNode loop = objectMapper.createObjectNode().put("index", count);
            loop.set("lastOutput", last);
            child.set("loop", loop);
            String path = parentPath.isBlank() ? String.valueOf(count) : parentPath + "/" + count;
            last = executeGraphNodes(runId, body, objectMapper.createObjectNode(), child, depth + 1, stack, sequence, path,
                workflowOwnerId, null);
            outputs.add(last);
            ((ObjectNode) context.path("loop")).put("index", count).set("lastOutput", last);
            count++;
        }
        return objectMapper.createObjectNode().put("iterations", count).set("items", outputs);
    }

    /** 确定性调用已发布子工作流并记录父子运行关系。 */
    private JsonNode executeSubWorkflow(String parentRunId, ObjectNode config, ObjectNode context,
                                        int depth, Set<String> stack, AtomicInteger sequence, Long workflowOwnerId) {
        JsonNode resolved = expressions.resolve(config, context);
        WorkflowNodeConfigValidator.validateResolved("SUB_WORKFLOW", resolved);
        WorkflowModels.StoredVersion version = workflowService.executableInternal(resolved.path("workflowCode").asText(), true);
        requireSameWorkflowOwner(version, workflowOwnerId);
        workflowService.validateExecutableConfiguration(version);
        ObjectNode arguments = resolved.path("inputs").isObject() ? (ObjectNode) resolved.path("inputs") : objectMapper.createObjectNode();
        validateInputs(version.inputSchema(), arguments);
        String childRunId = UUID.randomUUID().toString();
        insertRun(childRunId, version, parentRunId, TraceContextHolder.currentTraceId().orElse(null), "SUB_WORKFLOW",
            arguments, version.workflowOwnerId(), parentApiKeyId(parentRunId));
        if (!markRunRunning(childRunId)) throw new TraceCancelledException(TraceContextHolder.currentTraceId().orElse(""));
        try {
            JsonNode output = executeGraph(childRunId, version, arguments, depth + 1, stack, sequence);
            if (!completeRunSuccess(childRunId, output)) {
                throw new TraceCancelledException(TraceContextHolder.currentTraceId().orElse(""));
            }
            return output;
        } catch (TraceCancelledException exception) {
            markCancelled(childRunId);
            throw exception;
        } catch (RuntimeException exception) {
            completeRunFailure(childRunId, errorMessages.encode(exception));
            throw exception;
        }
    }

    /** 解析并限制等待节点时长。 */
    private long waitMilliseconds(ObjectNode config, ObjectNode context) {
        JsonNode resolved = expressions.resolve(config, context);
        long milliseconds = resolved.has("milliseconds") ? resolved.path("milliseconds").asLong()
            : resolved.path("seconds").asLong(1) * 1000L;
        long maximum = Math.max(1_000L, limits.getMaxWaitSeconds() * 1000L);
        if (milliseconds < 0 || milliseconds > maximum) throw new BusinessException("workflow.waitLimit", limits.getMaxWaitSeconds());
        return milliseconds;
    }

    /** 在最大步骤内让模型选择 HTTP 或已发布子工作流工具。 */
    private JsonNode executeAgent(String runId, ObjectNode config, ObjectNode context, int depth, Set<String> stack,
                                  AtomicInteger sequence, Long workflowOwnerId) {
        JsonNode resolved = expressions.resolve(config, context);
        WorkflowNodeConfigValidator.validateResolved("AGENT", resolved);
        int maximum = bounded(resolved.path("maxSteps").asInt(5), limits.getMaxAgentSteps());
        List<WorkflowAgentClient.Tool> tools = new ArrayList<>();
        Map<String, JsonNode> toolConfigs = new LinkedHashMap<>();
        resolved.path("tools").forEach(tool -> {
            String name = tool.path("name").asText();
            if (name.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}") && !toolConfigs.containsKey(name)) {
                tools.add(new WorkflowAgentClient.Tool(name, tool.path("description").asText(name),
                    tool.path("parameters").isObject() ? tool.path("parameters") : objectMapper.createObjectNode().put("type", "object")));
                toolConfigs.put(name, tool);
            }
        });
        if (tools.isEmpty()) throw new BusinessException("workflow.agentToolsRequired");
        List<Map<String, Object>> messages = new ArrayList<>();
        String systemPrompt = resolved.path("systemPrompt").asText("Use the provided tools to complete the task.");
        messages.add(new LinkedHashMap<>(Map.of("role", "system", "content", systemPrompt)));
        messages.add(new LinkedHashMap<>(Map.of("role", "user", "content", resolved.path("prompt").asText(context.path("input").toString()))));
        for (int step = 0; step < maximum; step++) {
            TraceContextHolder.checkpoint();
            WorkflowAgentClient.AgentStep decision = agentClient.step(resolved, messages, tools);
            if (decision.toolCalls().isEmpty()) {
                return objectMapper.createObjectNode().put("content", decision.content()).put("model", decision.model()).put("steps", step + 1);
            }
            List<Map<String, Object>> rawCalls = new ArrayList<>();
            for (WorkflowAgentClient.ToolCall call : decision.toolCalls()) {
                JsonNode tool = toolConfigs.get(call.name());
                if (tool == null) throw new BusinessException("workflow.agentToolForbidden", call.name());
                rawCalls.add(Map.of("id", call.id(), "type", "function", "function",
                    Map.of("name", call.name(), "arguments", call.arguments().toString())));
            }
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant"); assistant.put("content", decision.content()); assistant.put("tool_calls", rawCalls);
            messages.add(assistant);
            for (WorkflowAgentClient.ToolCall call : decision.toolCalls()) {
                JsonNode toolResult = executeAgentTool(runId, toolConfigs.get(call.name()), call.arguments(), depth, stack,
                    sequence, workflowOwnerId);
                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool"); toolMessage.put("tool_call_id", call.id());
                toolMessage.put("name", call.name()); toolMessage.put("content", toolResult.toString());
                messages.add(toolMessage);
            }
        }
        throw new BusinessException("workflow.agentStepLimit", maximum);
    }

    /** 执行 Agent 已授权的具体工具。 */
    private JsonNode executeAgentTool(String parentRunId, JsonNode tool, JsonNode arguments, int depth, Set<String> stack,
                                      AtomicInteger sequence, Long workflowOwnerId) {
        String type = tool.path("toolType").asText().toUpperCase(Locale.ROOT);
        if ("HTTP".equals(type)) {
            ObjectNode toolContext = objectMapper.createObjectNode();
            toolContext.set("input", arguments); toolContext.set("nodes", objectMapper.createObjectNode());
            toolContext.set("loop", objectMapper.createObjectNode());
            return executeHttp((ObjectNode) tool.path("config"), toolContext);
        }
        if ("WORKFLOW".equals(type)) {
            WorkflowModels.StoredVersion version = workflowService.executableInternal(tool.path("workflowCode").asText(), true);
            requireSameWorkflowOwner(version, workflowOwnerId);
            workflowService.validateExecutableConfiguration(version);
            validateInputs(version.inputSchema(), object(arguments));
            String childRunId = UUID.randomUUID().toString();
            Long ownerId = TraceContextHolder.current().map(TraceContext::ownerUserId).orElseThrow();
            insertRun(childRunId, version, parentRunId, TraceContextHolder.currentTraceId().orElse(null), "AGENT", arguments,
                ownerId, parentApiKeyId(parentRunId));
            if (!markRunRunning(childRunId)) throw new TraceCancelledException(TraceContextHolder.currentTraceId().orElse(""));
            try {
                JsonNode output = executeGraph(childRunId, version, object(arguments), depth + 1, stack, sequence);
                if (!completeRunSuccess(childRunId, output)) {
                    throw new TraceCancelledException(TraceContextHolder.currentTraceId().orElse(""));
                }
                return output;
            } catch (TraceCancelledException exception) {
                markCancelled(childRunId);
                throw exception;
            } catch (RuntimeException exception) {
                completeRunFailure(childRunId, errorMessages.encode(exception));
                throw exception;
            }
        }
        throw new BusinessException("workflow.agentToolForbidden", type);
    }

    /** 查询当前用户可见的运行详情和节点日志。 */
    public WorkflowModels.RunView run(String runId) {
        List<WorkflowModels.RunView> rows = jdbcTemplate.query("""
            SELECT r.*,d.code workflow_code,v.version_number FROM workflow_run r
            JOIN workflow_definition d ON d.id=r.workflow_id JOIN workflow_version v ON v.id=r.workflow_version_id WHERE r.id=?
            """, (rs, row) -> mapRun(rs), runId);
        if (rows.isEmpty()) throw BusinessException.notFound("workflow.runNotFound");
        WorkflowModels.RunView run = rows.get(0);
        accessService.requireRunAccess(run.workflowId(), run.ownerUserId(), run.apiKeyId());
        return new WorkflowModels.RunView(run.id(), run.workflowId(), run.workflowCode(), run.versionNumber(), run.parentRunId(),
            run.traceId(), run.triggerType(), run.status(), run.input(), run.output(), run.errorMessage(), run.ownerUserId(),
            run.apiKeyId(), run.cancelRequested(), run.startedAt(), run.finishedAt(), run.createdAt(), nodeRuns(runId));
    }

    /** 查询工作流最近运行记录。 */
    public List<WorkflowModels.RunView> runs(Long workflowId) {
        WorkflowModels.WorkflowView workflow = workflowService.workflow(workflowId);
        AuthUser user = AuthContext.require();
        boolean admin = user.roles().contains("ADMIN");
        String sql = """
            SELECT r.*,d.code workflow_code,v.version_number FROM workflow_run r
            JOIN workflow_definition d ON d.id=r.workflow_id JOIN workflow_version v ON v.id=r.workflow_version_id
            WHERE r.workflow_id=? %s ORDER BY r.created_at DESC LIMIT 200
            """.formatted(admin ? "" : "AND r.owner_user_id=" + user.id());
        return jdbcTemplate.query(sql, (rs, row) -> mapRun(rs), workflow.id());
    }

    /** 请求取消排队中或运行中的任务。 */
    public WorkflowModels.RunView cancel(String runId) {
        WorkflowModels.RunView existing = run(runId);
        if (!Set.of("QUEUED", "RUNNING", "WAITING").contains(existing.status())) return existing;
        jdbcTemplate.update("""
            UPDATE workflow_run SET cancel_requested=true,status='CANCELLED',finished_at=NOW(),updated_at=NOW()
            WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING')
            """, runId);
        jdbcTemplate.update("DELETE FROM workflow_wait_state WHERE workflow_run_id=?", runId);
        if (existing.traceId() != null) runtimeRegistry.cancel(existing.traceId());
        Future<?> future = futures.get(runId);
        if (future != null) future.cancel(true);
        if (existing.traceId() != null) taskTraceService.completeCancellation(existing.traceId());
        return run(runId);
    }

    /** 插入工作流运行记录。 */
    private void insertRun(String runId, WorkflowModels.StoredVersion version, String parentRunId, String traceId,
                           String triggerType, JsonNode inputs, Long ownerId, Long apiKeyId) {
        jdbcTemplate.update("""
            INSERT INTO workflow_run(id,workflow_id,workflow_version_id,parent_run_id,trace_id,trigger_type,status,input_encrypted,
                output_encrypted,owner_user_id,api_key_id,execution_instance_id,lease_expires_at)
            VALUES (?,?,?,?,?,?,'QUEUED',?,'',?,?,?,?)
            """, runId, version.workflowId(), version.id(), parentRunId, traceId, triggerType, encrypt(inputs), ownerId,
            apiKeyId, instanceId, leaseTimestamp());
    }

    /** 标记运行开始。 */
    private boolean markRunRunning(String runId) {
        return jdbcTemplate.update("""
            UPDATE workflow_run SET status='RUNNING',started_at=COALESCE(started_at,NOW()),execution_instance_id=?,
                lease_expires_at=?,updated_at=NOW() WHERE id=? AND status='QUEUED' AND cancel_requested=false
            """, instanceId, leaseTimestamp(), runId) == 1;
    }

    /** 仅允许活跃且未取消的运行进入成功终态，供状态机测试验证竞争行为。 */
    boolean completeRunSuccess(String runId, JsonNode output) {
        return jdbcTemplate.update("""
            UPDATE workflow_run SET status='SUCCESS',output_encrypted=?,finished_at=NOW(),lease_expires_at=NULL,updated_at=NOW()
            WHERE id=? AND status='RUNNING' AND cancel_requested=false
            """, encrypt(output), runId) == 1;
    }

    /** 仅允许活跃且未取消的运行进入失败终态，防止子运行迟到异常覆盖取消结果。 */
    boolean completeRunFailure(String runId, String message) {
        return jdbcTemplate.update("""
            UPDATE workflow_run SET status='FAILED',error_message=?,finished_at=NOW(),lease_expires_at=NULL,updated_at=NOW()
            WHERE id=? AND status IN ('QUEUED','RUNNING') AND cancel_requested=false
            """, truncate(message, 2000), runId) == 1;
    }

    /** 标记运行取消。 */
    private void markCancelled(String runId) {
        jdbcTemplate.update("""
            UPDATE workflow_run SET status='CANCELLED',cancel_requested=true,finished_at=NOW(),updated_at=NOW()
            WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING','CANCELLED')
            """, runId);
        jdbcTemplate.update("UPDATE workflow_run SET lease_expires_at=NULL WHERE id=?", runId);
        jdbcTemplate.update("DELETE FROM workflow_wait_state WHERE workflow_run_id=?", runId);
    }

    /** 将等待节点运行日志标记为等待中。 */
    private void markNodeWaiting(long nodeRunId) {
        jdbcTemplate.update("UPDATE workflow_node_run SET status='WAITING' WHERE id=? AND status='RUNNING'", nodeRunId);
    }

    /** 加密保存等待检查点并释放执行线程。 */
    private void persistWait(String runId, WaitCheckpoint checkpoint) {
        Instant resumeAt = Instant.now().plusMillis(checkpoint.waitedMilliseconds());
        JsonNode state = objectMapper.valueToTree(checkpoint);
        int persisted = jdbcTemplate.update("""
            INSERT INTO workflow_wait_state(workflow_run_id,node_id,resume_at,state_encrypted,status)
            SELECT ?,?,?,?,'WAITING' FROM workflow_run
            WHERE id=? AND status='RUNNING' AND cancel_requested=false
            ON DUPLICATE KEY UPDATE node_id=VALUES(node_id),resume_at=VALUES(resume_at),state_encrypted=VALUES(state_encrypted),
                status='WAITING',updated_at=NOW()
            """, runId, checkpoint.waitNodeId(), java.sql.Timestamp.from(resumeAt), encrypt(state), runId);
        if (persisted == 0) throw new TraceCancelledException(TraceContextHolder.currentTraceId().orElse(""));
        int waiting = jdbcTemplate.update("""
            UPDATE workflow_run SET status='WAITING',execution_instance_id=NULL,lease_expires_at=NULL,updated_at=NOW()
            WHERE id=? AND status='RUNNING' AND cancel_requested=false
            """, runId);
        if (waiting == 0) {
            jdbcTemplate.update("DELETE FROM workflow_wait_state WHERE workflow_run_id=?", runId);
            throw new TraceCancelledException(TraceContextHolder.currentTraceId().orElse(""));
        }
    }

    /** 每秒领取到期等待记录并提交恢复任务。 */
    @Scheduled(fixedDelay = 1_000L)
    public void resumeDueWaits() {
        List<WaitResumeRow> due = jdbcTemplate.query("""
            SELECT w.workflow_run_id,w.state_encrypted,r.workflow_version_id,r.trace_id,r.owner_user_id,r.trigger_type
            FROM workflow_wait_state w JOIN workflow_run r ON r.id=w.workflow_run_id
            WHERE w.status='WAITING' AND w.resume_at<=NOW() AND r.status='WAITING' AND r.cancel_requested=false
            ORDER BY w.resume_at LIMIT 50
            """, (rs, row) -> new WaitResumeRow(rs.getString("workflow_run_id"), rs.getString("state_encrypted"),
            rs.getLong("workflow_version_id"), rs.getString("trace_id"), rs.getLong("owner_user_id"), rs.getString("trigger_type")));
        for (WaitResumeRow row : due) {
            int claimed = jdbcTemplate.update("""
                UPDATE workflow_run SET status='RUNNING',execution_instance_id=?,lease_expires_at=?,updated_at=NOW()
                WHERE id=? AND status='WAITING' AND cancel_requested=false AND EXISTS (
                    SELECT 1 FROM workflow_wait_state w WHERE w.workflow_run_id=workflow_run.id AND w.status='WAITING')
                """, instanceId, leaseTimestamp(), row.runId());
            if (claimed == 1) {
                int checkpoint = jdbcTemplate.update("""
                    UPDATE workflow_wait_state SET status='RESUMING',updated_at=NOW()
                    WHERE workflow_run_id=? AND status='WAITING'
                    """, row.runId());
                if (checkpoint == 1) submitResume(row);
                else jdbcTemplate.update("""
                    UPDATE workflow_run SET status='WAITING',execution_instance_id=NULL,lease_expires_at=NULL,updated_at=NOW()
                    WHERE id=? AND status='RUNNING' AND cancel_requested=false AND execution_instance_id=?
                    """, row.runId(), instanceId);
            }
        }
    }

    /** 周期性续租本实例排队和运行中的任务，避免其他实例误判为遗留任务。 */
    @Scheduled(fixedDelay = 10_000L)
    public void heartbeatLeases() {
        jdbcTemplate.update("""
            UPDATE workflow_run SET lease_expires_at=?,updated_at=NOW()
            WHERE execution_instance_id=? AND status IN ('QUEUED','RUNNING') AND cancel_requested=false
            """, leaseTimestamp(), instanceId);
    }

    /** 周期性回收过期恢复租约，并只将没有等待检查点的遗留运行置为失败。 */
    @Scheduled(fixedDelay = 15_000L)
    public void recoverExpiredLeases() {
        jdbcTemplate.update("""
            UPDATE workflow_run SET status='WAITING',execution_instance_id=NULL,lease_expires_at=NULL,updated_at=NOW()
            WHERE status='RUNNING' AND lease_expires_at<NOW() AND EXISTS (
                SELECT 1 FROM workflow_wait_state w WHERE w.workflow_run_id=workflow_run.id AND w.status='RESUMING')
            """);
        jdbcTemplate.update("""
            UPDATE workflow_wait_state SET status='WAITING',updated_at=NOW()
            WHERE status='RESUMING' AND EXISTS (
                SELECT 1 FROM workflow_run r WHERE r.id=workflow_wait_state.workflow_run_id AND r.status='WAITING')
            """);
        List<Map<String, Object>> expired = jdbcTemplate.queryForList("""
            SELECT r.id,r.trace_id FROM workflow_run r LEFT JOIN workflow_wait_state w ON w.workflow_run_id=r.id
            WHERE r.status IN ('QUEUED','RUNNING') AND r.cancel_requested=false AND r.lease_expires_at<NOW()
                AND w.workflow_run_id IS NULL
            """);
        for (Map<String, Object> row : expired) {
            String runId = String.valueOf(row.get("id"));
            String traceId = row.get("trace_id") == null ? null : String.valueOf(row.get("trace_id"));
            String message = errorMessages.encode("workflow.executionLeaseExpired");
            int failed = jdbcTemplate.update("""
                UPDATE workflow_run SET status='FAILED',error_message=?,finished_at=NOW(),execution_instance_id=NULL,
                    lease_expires_at=NULL,updated_at=NOW()
                WHERE id=? AND status IN ('QUEUED','RUNNING') AND cancel_requested=false AND lease_expires_at<NOW()
                """, message, runId);
            if (failed == 1 && traceId != null) taskTraceService.markFailed(traceId, readableError(message));
        }
    }

    /** 为已领取检查点创建新的可取消运行时。 */
    private void submitResume(WaitResumeRow row) {
        TraceRuntime runtime = runtimeRegistry.create(row.traceId());
        try {
            Future<?> future = executor.submit(() -> executeResumed(row, runtime));
            runtime.registerFuture(future); futures.put(row.runId(), future);
            if (future.isDone()) futures.remove(row.runId(), future);
        } catch (TaskRejectedException exception) {
            jdbcTemplate.update("""
                UPDATE workflow_run SET status='WAITING',execution_instance_id=NULL,lease_expires_at=NULL,updated_at=NOW()
                WHERE id=? AND status='RUNNING' AND cancel_requested=false AND execution_instance_id=?
                """, row.runId(), instanceId);
            jdbcTemplate.update("""
                UPDATE workflow_wait_state SET status='WAITING',updated_at=NOW()
                WHERE workflow_run_id=? AND status='RESUMING'
                """, row.runId());
            runtimeRegistry.remove(row.traceId());
        }
    }

    /** 绑定原 Trace 并从等待节点之后恢复拓扑执行。 */
    private void executeResumed(WaitResumeRow row, TraceRuntime runtime) {
        budgetRunId.set(row.runId());
        runtime.registerThread(Thread.currentThread());
        TraceContext trace = new TraceContext(row.traceId(), row.ownerId(), "WORKFLOW_EXECUTE", row.triggerType(),
            runtime.token(), runtime);
        try (TraceContextHolder.Scope ignored = TraceContextHolder.bind(trace)) {
            taskTraceService.resumeWaiting(row.traceId());
            WorkflowModels.StoredVersion version = workflowService.storedVersion(row.versionId());
            WaitCheckpoint checkpoint = objectMapper.treeToValue(decrypt(row.stateEncrypted()), WaitCheckpoint.class);
            Set<String> stack = new LinkedHashSet<>(); stack.add(version.workflowCode());
            ObjectNode context = checkpoint.context().deepCopy();
            JsonNode output = executeGraphNodes(row.runId(), version.graph(), version.templateSnapshots(), context, 0, stack,
                new AtomicInteger(checkpoint.sequence()), "", version.workflowOwnerId(), checkpoint);
            enforcePayload(output);
            int completed = jdbcTemplate.update("""
                UPDATE workflow_run SET status='SUCCESS',output_encrypted=?,finished_at=NOW(),lease_expires_at=NULL,updated_at=NOW()
                WHERE id=? AND status='RUNNING' AND cancel_requested=false AND execution_instance_id=?
                """, encrypt(output), row.runId(), instanceId);
            if (completed == 0) throw new TraceCancelledException(row.traceId());
            jdbcTemplate.update("DELETE FROM workflow_wait_state WHERE workflow_run_id=?", row.runId());
            taskTraceService.markSuccess(row.traceId());
        } catch (WorkflowWaitSignal signal) {
            try {
                persistWait(row.runId(), signal.checkpoint()); taskTraceService.markWaiting(row.traceId());
            } catch (TraceCancelledException exception) {
                markCancelled(row.runId()); taskTraceService.completeCancellation(row.traceId());
            }
        } catch (TraceCancelledException exception) {
            markCancelled(row.runId()); taskTraceService.completeCancellation(row.traceId());
        } catch (Throwable exception) {
            String message = errorMessages.encode(exception);
            int failed = jdbcTemplate.update("""
                UPDATE workflow_run SET status='FAILED',error_message=?,finished_at=NOW(),lease_expires_at=NULL,updated_at=NOW()
                WHERE id=? AND status='RUNNING' AND cancel_requested=false AND execution_instance_id=?
                """, message, row.runId(), instanceId);
            jdbcTemplate.update("DELETE FROM workflow_wait_state WHERE workflow_run_id=?", row.runId());
            if (failed == 1) taskTraceService.markFailed(row.traceId(), readableError(message));
            else { markCancelled(row.runId()); taskTraceService.completeCancellation(row.traceId()); }
        } finally {
            futures.remove(row.runId()); runtime.unregisterThread(Thread.currentThread()); runtimeRegistry.remove(row.traceId());
            budgetRunId.remove();
            Thread.interrupted();
        }
    }

    /** 写入节点运行开始记录并返回主键。 */
    private long startNodeRun(String runId, JsonNode node, String type, int sequence, String iterationPath, JsonNode input) {
        reserveLogBytes(runId, input, null);
        Long id = insertKey("""
            INSERT INTO workflow_node_run(workflow_run_id,node_id,node_name,default_node_name,localization_json,node_type,
                sequence_no,iteration_path,status,input_encrypted,output_encrypted)
            VALUES (?,?,?,?,?,?,?,?,'RUNNING',?,'')
            """, runId, node.path("id").asText(), node.path("data").path("label").asText(type),
            node.path("data").path("defaultLabel").asText(""), displayLocalization(node.path("data").path("localization")),
            type, sequence, iterationPath, encrypt(input));
        return id == null ? 0 : id;
    }

    /** 写入节点终态、输出或错误。 */
    private void finishNodeRun(long id, String status, JsonNode output, String error) {
        String runId = jdbcTemplate.queryForObject("SELECT workflow_run_id FROM workflow_node_run WHERE id=?", String.class, id);
        reserveLogBytes(runId, output, error);
        jdbcTemplate.update("""
            UPDATE workflow_node_run SET status=?,output_encrypted=?,error_message=?,finished_at=NOW() WHERE id=?
            """, status, output == null ? "" : encrypt(output), error, id);
    }

    /** 返回稳定拓扑顺序。 */
    private List<String> topologicalOrder(Map<String, JsonNode> nodes, List<JsonNode> edges) {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> { incoming.put(id, 0); outgoing.put(id, new ArrayList<>()); });
        edges.forEach(edge -> {
            String source = edge.path("source").asText(); String target = edge.path("target").asText();
            outgoing.get(source).add(target); incoming.put(target, incoming.get(target) + 1);
        });
        ArrayDeque<String> queue = new ArrayDeque<>();
        incoming.forEach((id, count) -> { if (count == 0) queue.add(id); });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.remove(); order.add(id);
            for (String target : outgoing.get(id)) if (incoming.compute(target, (ignored, count) -> count - 1) == 0) queue.add(target);
        }
        if (order.size() != nodes.size()) throw new BusinessException("workflow.graphCycle");
        return order;
    }

    /** 合并模板默认配置和画布实例覆盖配置。 */
    private ObjectNode nodeConfig(JsonNode node, JsonNode snapshot) {
        ObjectNode result = objectMapper.createObjectNode();
        if (snapshot.path("config").isObject()) result.setAll((ObjectNode) snapshot.path("config").deepCopy());
        JsonNode own = node.path("config").isObject() ? node.path("config") : node.path("data").path("config");
        if (own.isObject()) deepMerge(result, own);
        return WorkflowNodeConfigDefaults.withDefaults(objectMapper, WorkflowGraphValidator.nodeType(node), result);
    }

    /** 递归合并实例配置，确保嵌套 HTTP、Agent 配置可局部覆盖。 */
    private void deepMerge(ObjectNode target, JsonNode source) {
        source.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject() && target.path(entry.getKey()).isObject()) {
                deepMerge((ObjectNode) target.path(entry.getKey()), entry.getValue());
            } else target.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }

    /** 根据 JSON Schema 的 required 和基础 type 校验开放输入。 */
    private void validateInputs(JsonNode schema, ObjectNode inputs) {
        if (schema == null || !schema.isObject()) return;
        schema.path("required").forEach(item -> {
            if (item.isTextual() && (!inputs.has(item.asText()) || inputs.path(item.asText()).isNull())) {
                throw new BusinessException("workflow.inputRequired", item.asText());
            }
        });
        schema.path("properties").fields().forEachRemaining(entry -> {
            if (!inputs.has(entry.getKey())) return;
            String type = entry.getValue().path("type").asText();
            JsonNode value = inputs.path(entry.getKey());
            boolean valid = switch (type) {
                case "string" -> value.isTextual(); case "number" -> value.isNumber(); case "integer" -> value.isIntegralNumber();
                case "boolean" -> value.isBoolean(); case "array" -> value.isArray(); case "object" -> value.isObject(); default -> true;
            };
            if (!valid) throw new BusinessException("workflow.inputTypeInvalid", entry.getKey(), type);
        });
    }

    /** 限制输入、输出和节点结果序列化后的体积。 */
    private void enforcePayload(JsonNode value) {
        if (json(value).getBytes(StandardCharsets.UTF_8).length > limits.getMaxPayloadBytes()) {
            throw new BusinessException("workflow.payloadTooLarge", limits.getMaxPayloadBytes());
        }
    }

    /** 查询运行关联的 traceId。 */
    private String traceId(String runId) {
        return jdbcTemplate.queryForObject("SELECT trace_id FROM workflow_run WHERE id=?", String.class, runId);
    }

    /** 查询并解密节点运行记录。 */
    private List<WorkflowModels.NodeRunView> nodeRuns(String runId) {
        return jdbcTemplate.query("SELECT * FROM workflow_node_run WHERE workflow_run_id=? ORDER BY sequence_no,id", (rs, row) ->
            new WorkflowModels.NodeRunView(rs.getLong("id"), rs.getString("node_id"), rs.getString("node_name"),
                rs.getString("node_type"), rs.getInt("sequence_no"), rs.getString("iteration_path"), rs.getString("status"),
                decrypt(rs.getString("input_encrypted")), decrypt(rs.getString("output_encrypted")), errorMessages.localize(rs.getString("error_message")),
                timestamp(rs, "started_at"), timestamp(rs, "finished_at"), rs.getString("default_node_name"),
                displayLocalization(rs.getString("localization_json"))), runId);
    }

    /** 只持久化节点名称的受控双语展示元数据。 */
    private String displayLocalization(JsonNode value) {
        ObjectNode result = objectMapper.createObjectNode();
        if (value == null || !value.isObject()) return result.toString();
        ObjectNode names = result.putObject("name");
        for (String locale : List.of("zh-CN", "en-US")) {
            String text = value.path("name").path(locale).asText("").trim();
            if (!text.isBlank()) names.put(locale, text.substring(0, Math.min(text.length(), 120)));
        }
        if (names.isEmpty()) result.remove("name");
        return result.toString();
    }

    /** 解析可信节点运行展示元数据，历史空值回退为空对象。 */
    private JsonNode displayLocalization(String value) {
        if (value == null || value.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed.isObject() ? parsed : objectMapper.createObjectNode();
        } catch (Exception exception) { return objectMapper.createObjectNode(); }
    }

    /** 映射并解密工作流运行记录。 */
    private WorkflowModels.RunView mapRun(ResultSet rs) throws SQLException {
        return new WorkflowModels.RunView(rs.getString("id"), rs.getLong("workflow_id"), rs.getString("workflow_code"),
            rs.getInt("version_number"), rs.getString("parent_run_id"), rs.getString("trace_id"), rs.getString("trigger_type"),
            rs.getString("status"), decrypt(rs.getString("input_encrypted")), decrypt(rs.getString("output_encrypted")),
            errorMessages.localize(rs.getString("error_message")), rs.getLong("owner_user_id"), (Long) rs.getObject("api_key_id"),
            rs.getBoolean("cancel_requested"),
            timestamp(rs, "started_at"), timestamp(rs, "finished_at"), timestamp(rs, "created_at"), List.of());
    }

    /** 将工具参数规范为对象输入。 */
    private ObjectNode object(JsonNode value) {
        if (!value.isObject()) throw new BusinessException("workflow.agentArgumentsInvalid");
        return (ObjectNode) value.deepCopy();
    }

    /** 将节点自定义上限限制在平台硬上限内。 */
    private int bounded(int requested, int maximum) { return Math.max(1, Math.min(requested, Math.max(1, maximum))); }
    /** 返回当前请求的 API Key ID，交互登录态返回空。 */
    private Long currentApiKeyId() {
        AuthUser user = AuthContext.require();
        return user.authenticationType() == AuthenticationType.API_KEY ? user.credentialId() : null;
    }
    /** 读取父运行来源 API Key，使同步子工作流保持同一结果访问边界。 */
    private Long parentApiKeyId(String parentRunId) {
        return jdbcTemplate.queryForObject("SELECT api_key_id FROM workflow_run WHERE id=?", Long.class, parentRunId);
    }
    /** 子工作流和 Agent 工具不得跨越所属用户调用其他用户的工作流。 */
    private void requireSameWorkflowOwner(WorkflowModels.StoredVersion version, Long ownerId) {
        if (!version.workflowOwnerId().equals(ownerId)) throw BusinessException.forbidden("workflow.subWorkflowForbidden");
    }
    /** 计算本实例下一次租约截止时间。 */
    private java.sql.Timestamp leaseTimestamp() {
        return java.sql.Timestamp.from(Instant.now().plusSeconds(Math.max(15, limits.getLeaseSeconds())));
    }
    /** 原子预留节点日志字节预算，避免累计上下文造成数据库空间放大。 */
    private void reserveLogBytes(String runId, JsonNode value, String error) {
        long bytes = json(value).getBytes(StandardCharsets.UTF_8).length
            + (error == null ? 0 : error.getBytes(StandardCharsets.UTF_8).length);
        long maximum = Math.max(limits.getMaxPayloadBytes(), limits.getMaxRunLogBytes());
        String budgetOwner = budgetRunId.get() == null ? runId : budgetRunId.get();
        int reserved = jdbcTemplate.update("""
            UPDATE workflow_run SET log_bytes=log_bytes+? WHERE id=? AND log_bytes+?<=?
            """, bytes, budgetOwner, bytes, maximum);
        if (reserved == 0) throw new BusinessException("workflow.runLogLimit", maximum);
    }
    /** 将 HTTP 正文配置规范为文本。 */
    private String bodyText(JsonNode value) { return value.isTextual() ? value.asText() : value.isMissingNode() ? "" : value.toString(); }
    /** 将 HTTP 结构化字段规范为 JSON 文本。 */
    private String jsonText(JsonNode value) { return value.isMissingNode() || value.isNull() ? "" : value.isTextual() ? value.asText() : value.toString(); }
    /** 加密工作流运行 JSON。 */
    private String encrypt(JsonNode value) { return cryptoService.encrypt(json(value)); }
    /** 解密工作流运行 JSON。 */
    private JsonNode decrypt(String value) {
        if (value == null || value.isBlank()) return objectMapper.nullNode();
        try { return objectMapper.readTree(cryptoService.decrypt(value)); }
        catch (Exception exception) { throw new BusinessException("workflow.jsonInvalid"); }
    }
    /** 序列化工作流运行 JSON。 */
    private String json(JsonNode value) {
        try { return objectMapper.writeValueAsString(value == null ? objectMapper.nullNode() : value); }
        catch (Exception exception) { throw new BusinessException("workflow.jsonInvalid"); }
    }
    /** 截断持久化错误信息并提供安全兜底文本。 */
    private String truncate(String value, int maximum) {
        String normalized = value == null || value.isBlank() ? "工作流执行失败" : value;
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
    /** 将持久化错误转换为稳定中文，避免异步任务追踪展示内部结构化标记。 */
    private String readableError(String persisted) {
        return errorMessages.localize(persisted, Locale.SIMPLIFIED_CHINESE);
    }
    /** 将 JDBC 时间戳安全映射为本地时间。 */
    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toLocalDateTime();
    }
    /** 使用 MySQL 自增主键写入节点运行记录。 */
    private Long insertKey(String sql, Object... arguments) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("MySQL 未返回节点运行自增主键");
        return key.longValue();
    }
    private record NodeResult(JsonNode output, String branch, String error) {}
    private record WaitCheckpoint(ObjectNode context, List<String> selectedEdges, int nextIndex, JsonNode finalOutput,
                                  int sequence, String waitNodeId, long nodeRunId, long waitedMilliseconds) {}
    private record WaitResumeRow(String runId, String stateEncrypted, Long versionId, String traceId,
                                 Long ownerId, String triggerType) {}
    private static final class WorkflowWaitSignal extends RuntimeException {
        private final WaitCheckpoint checkpoint;
        /** 使用无堆栈内部信号释放执行线程。 */
        private WorkflowWaitSignal(WaitCheckpoint checkpoint) { super(null, null, false, false); this.checkpoint = checkpoint; }
        /** 返回需要持久化的等待检查点。 */
        private WaitCheckpoint checkpoint() { return checkpoint; }
    }
}
