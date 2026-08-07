package com.baseai.platform.workflow;

import com.baseai.platform.automation.ApiTriggerModels;
import com.baseai.platform.automation.ApiTriggerService;
import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
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
    private final ThreadPoolTaskExecutor executor;
    private final TaskTraceService taskTraceService;
    private final TraceRuntimeRegistry runtimeRegistry;
    private final PlatformProperties.Workflow limits;
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();

    /** 注入执行所需的持久化、节点适配器、线程池和追踪组件。 */
    public WorkflowExecutionService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper, ConfigCryptoService cryptoService,
                                    WorkflowService workflowService, WorkflowExpressionService expressions,
                                    WorkflowAgentClient agentClient, AiChatClient aiChatClient,
                                    ApiTriggerService apiTriggerService,
                                    @Qualifier("workflowTaskExecutor") ThreadPoolTaskExecutor executor,
                                    TaskTraceService taskTraceService, TraceRuntimeRegistry runtimeRegistry,
                                    PlatformProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.workflowService = workflowService;
        this.expressions = expressions;
        this.agentClient = agentClient;
        this.aiChatClient = aiChatClient;
        this.apiTriggerService = apiTriggerService;
        this.executor = executor;
        this.taskTraceService = taskTraceService;
        this.runtimeRegistry = runtimeRegistry;
        this.limits = properties.getWorkflow();
    }

    /** 启动时将上次进程遗留的运行中记录置为失败，首期不执行断点恢复。 */
    @Override
    public void run(ApplicationArguments arguments) {
        jdbcTemplate.update("""
            UPDATE workflow_run SET status='FAILED',error_message='服务重启，工作流未恢复',finished_at=NOW(),updated_at=NOW()
            WHERE status IN ('QUEUED','RUNNING')
            """);
    }

    /** 从画布启动当前草稿版本。 */
    public WorkflowModels.RunAccepted startDraft(Long workflowId, Map<String, Object> inputs) {
        WorkflowModels.WorkflowView workflow = workflowService.workflow(workflowId);
        WorkflowModels.StoredVersion version = workflowService.storedVersion(workflow.currentVersionId());
        return enqueue(version, inputs, "MANUAL", AuthContext.require().id(), null, 0, Set.of());
    }

    /** 从开放平台按稳定编码启动已发布版本。 */
    public WorkflowModels.RunAccepted startPublished(String workflowCode, Map<String, Object> inputs) {
        WorkflowModels.StoredVersion version = workflowService.executable(workflowCode, true);
        return enqueue(version, inputs, "API", AuthContext.require().id(), null, 0, Set.of());
    }

    /** 创建异步运行记录、MySQL 任务追踪和可取消 Future。 */
    private WorkflowModels.RunAccepted enqueue(WorkflowModels.StoredVersion version, Map<String, Object> rawInputs,
                                                String triggerType, Long ownerId, String parentRunId,
                                                int depth, Set<String> stack) {
        ObjectNode inputs = objectMapper.valueToTree(rawInputs == null ? Map.of() : rawInputs);
        validateInputs(version.inputSchema(), inputs);
        enforcePayload(inputs);
        String runId = UUID.randomUUID().toString();
        String traceId = taskTraceService.create(null, ownerId, "WORKFLOW_EXECUTE", triggerType, "POST",
            "/api/workflows/" + version.workflowCode() + "/runs", new TraceSnapshot("{}", "{}"));
        insertRun(runId, version, parentRunId, traceId, triggerType, inputs, ownerId);
        TraceRuntime runtime = runtimeRegistry.create(traceId);
        Future<?> future = executor.submit(() -> executeQueued(runId, version, inputs, ownerId, triggerType, depth, stack, runtime));
        runtime.registerFuture(future);
        futures.put(runId, future);
        return new WorkflowModels.RunAccepted(runId, "QUEUED");
    }

    /** 在线程池中绑定追踪上下文并维护统一终态。 */
    private void executeQueued(String runId, WorkflowModels.StoredVersion version, ObjectNode inputs, Long ownerId,
                               String triggerType, int depth, Set<String> stack, TraceRuntime runtime) {
        String traceId = traceId(runId);
        runtime.registerThread(Thread.currentThread());
        TraceContext context = new TraceContext(traceId, ownerId, "WORKFLOW_EXECUTE", triggerType, runtime.token(), runtime);
        try (TraceContextHolder.Scope ignored = TraceContextHolder.bind(context)) {
            markRunRunning(runId);
            JsonNode output = executeGraph(runId, version, inputs, depth, stack, new AtomicInteger());
            enforcePayload(output);
            jdbcTemplate.update("""
                UPDATE workflow_run SET status='SUCCESS',output_encrypted=?,finished_at=NOW(),updated_at=NOW() WHERE id=?
                """, encrypt(output), runId);
            taskTraceService.markSuccess(traceId);
        } catch (TraceCancelledException exception) {
            markCancelled(runId);
            taskTraceService.completeCancellation(traceId);
        } catch (Throwable exception) {
            if (runtime.token().isCancelled() || Thread.currentThread().isInterrupted()) {
                markCancelled(runId);
                taskTraceService.completeCancellation(traceId);
            } else {
                String message = truncate(exception.getMessage(), 2000);
                jdbcTemplate.update("""
                    UPDATE workflow_run SET status='FAILED',error_message=?,finished_at=NOW(),updated_at=NOW() WHERE id=?
                    """, message, runId);
                taskTraceService.markFailed(traceId, message);
            }
        } finally {
            futures.remove(runId);
            runtime.unregisterThread(Thread.currentThread());
            runtimeRegistry.remove(traceId);
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
        return executeGraphNodes(runId, version.graph(), version.templateSnapshots(), context, depth, nextStack, sequence, "");
    }

    /** 执行主图或循环子图中的节点，并返回结束节点输出。 */
    private JsonNode executeGraphNodes(String runId, JsonNode graph, JsonNode snapshots, ObjectNode context,
                                       int depth, Set<String> stack, AtomicInteger sequence, String iterationPath) {
        if (depth > limits.getMaxRecursionDepth()) throw new BusinessException("workflow.recursionLimit");
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        graph.path("nodes").forEach(node -> nodes.put(node.path("id").asText(), node));
        List<JsonNode> edges = new ArrayList<>();
        graph.path("edges").forEach(edges::add);
        List<String> order = topologicalOrder(nodes, edges);
        Set<String> selectedEdges = new HashSet<>();
        JsonNode finalOutput = objectMapper.createObjectNode();
        for (String nodeId : order) {
            TraceContextHolder.checkpoint();
            JsonNode node = nodes.get(nodeId);
            String type = WorkflowGraphValidator.nodeType(node);
            List<JsonNode> incoming = edges.stream().filter(edge -> nodeId.equals(edge.path("target").asText())).toList();
            if (!"START".equals(type) && incoming.stream().noneMatch(edge -> selectedEdges.contains(edge.path("id").asText()))) continue;
            ObjectNode config = nodeConfig(node, snapshots.path(nodeId));
            JsonNode nodeInput = objectMapper.createObjectNode().set("context", context.deepCopy());
            long nodeRunId = startNodeRun(runId, node, type, sequence.incrementAndGet(), iterationPath, nodeInput);
            try {
                NodeResult result = executeNode(runId, node, type, config, context, depth, stack, sequence, iterationPath);
                ((ObjectNode) context.path("nodes")).set(nodeId, result.output());
                finishNodeRun(nodeRunId, "SUCCESS", result.output(), null);
                if ("END".equals(type)) finalOutput = result.output();
                for (JsonNode edge : edges.stream().filter(item -> nodeId.equals(item.path("source").asText())).toList()) {
                    String handle = edge.path("sourceHandle").asText("").toLowerCase(Locale.ROOT);
                    if (result.branch() == null || handle.isBlank() || handle.equals(String.valueOf(result.branch()))) {
                        selectedEdges.add(edge.path("id").asText());
                    }
                }
            } catch (RuntimeException exception) {
                finishNodeRun(nodeRunId, "FAILED", null, truncate(exception.getMessage(), 2000));
                throw exception;
            }
        }
        return finalOutput;
    }

    /** 分派单个节点类型并生成节点输出及可选条件分支。 */
    private NodeResult executeNode(String runId, JsonNode node, String type, ObjectNode config, ObjectNode context,
                                   int depth, Set<String> stack, AtomicInteger sequence, String iterationPath) {
        return switch (type) {
            case "START" -> new NodeResult(context.path("input").deepCopy(), null);
            case "END" -> new NodeResult(config.has("output") ? expressions.resolve(config.path("output"), context)
                : context.path("nodes").deepCopy(), null);
            case "LLM" -> new NodeResult(executeLlm(config, context), null);
            case "HTTP" -> new NodeResult(executeHttp(config, context), null);
            case "CONDITION" -> {
                boolean matched = expressions.condition(config.path("condition"), context);
                yield new NodeResult(objectMapper.createObjectNode().put("matched", matched), matched);
            }
            case "ITERATION" -> new NodeResult(executeIteration(runId, config, context, depth, stack, sequence, iterationPath), null);
            case "LOOP" -> new NodeResult(executeLoop(runId, config, context, depth, stack, sequence, iterationPath), null);
            case "AGENT" -> new NodeResult(executeAgent(runId, config, context, depth, stack), null);
            default -> throw new BusinessException("workflow.nodeTypeInvalid");
        };
    }

    /** 调用现有模型路由执行 LLM 节点。 */
    private JsonNode executeLlm(ObjectNode config, ObjectNode context) {
        JsonNode resolved = expressions.resolve(config, context);
        List<AiChatClient.Message> messages = new ArrayList<>();
        String systemPrompt = resolved.path("systemPrompt").asText("");
        if (!systemPrompt.isBlank()) messages.add(new AiChatClient.Message("system", systemPrompt));
        String prompt = resolved.path("prompt").asText(context.path("input").toString());
        if (prompt.isBlank()) prompt = context.path("input").toString();
        messages.add(new AiChatClient.Message("user", prompt));
        Long modelId = resolved.hasNonNull("modelId") ? resolved.path("modelId").asLong() : null;
        AiChatClient.ChatResult result = aiChatClient.chat(resolved.path("featureCode").asText("DEFAULT"),
            resolved.path("modelType").asText("text_model"), messages, resolved.path("temperature").asDouble(0),
            resolved.has("enableThinking") ? resolved.path("enableThinking").asBoolean() : null,
            resolved.path("thinkingLevel").asText(null), modelId);
        return objectMapper.createObjectNode().put("content", result.content()).put("model", result.model())
            .put("inputTokens", result.inputTokens()).put("outputTokens", result.outputTokens()).put("totalTokens", result.totalTokens());
    }

    /** 将节点配置映射为接口触发命令，复用已有 SSRF 和 TLS 安全实现。 */
    private JsonNode executeHttp(ObjectNode config, ObjectNode context) {
        JsonNode resolved = expressions.resolve(config, context);
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
                                      AtomicInteger sequence, String parentPath) {
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
            outputs.add(executeGraphNodes(runId, body, objectMapper.createObjectNode(), child, depth + 1, stack, sequence, path));
        }
        return objectMapper.createObjectNode().put("iterations", collection.size()).set("items", outputs);
    }

    /** 在退出条件或硬上限触发前重复执行子画布。 */
    private JsonNode executeLoop(String runId, ObjectNode config, ObjectNode context, int depth, Set<String> stack,
                                 AtomicInteger sequence, String parentPath) {
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
            last = executeGraphNodes(runId, body, objectMapper.createObjectNode(), child, depth + 1, stack, sequence, path);
            outputs.add(last);
            ((ObjectNode) context.path("loop")).put("index", count).set("lastOutput", last);
            count++;
        }
        return objectMapper.createObjectNode().put("iterations", count).set("items", outputs);
    }

    /** 在最大步骤内让模型选择 HTTP 或已发布子工作流工具。 */
    private JsonNode executeAgent(String runId, ObjectNode config, ObjectNode context, int depth, Set<String> stack) {
        JsonNode resolved = expressions.resolve(config, context);
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
                JsonNode toolResult = executeAgentTool(runId, toolConfigs.get(call.name()), call.arguments(), depth, stack);
                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool"); toolMessage.put("tool_call_id", call.id());
                toolMessage.put("name", call.name()); toolMessage.put("content", toolResult.toString());
                messages.add(toolMessage);
            }
        }
        throw new BusinessException("workflow.agentStepLimit", maximum);
    }

    /** 执行 Agent 已授权的具体工具。 */
    private JsonNode executeAgentTool(String parentRunId, JsonNode tool, JsonNode arguments, int depth, Set<String> stack) {
        String type = tool.path("toolType").asText().toUpperCase(Locale.ROOT);
        if ("HTTP".equals(type)) {
            ObjectNode toolContext = objectMapper.createObjectNode();
            toolContext.set("input", arguments); toolContext.set("nodes", objectMapper.createObjectNode());
            toolContext.set("loop", objectMapper.createObjectNode());
            return executeHttp((ObjectNode) tool.path("config"), toolContext);
        }
        if ("WORKFLOW".equals(type)) {
            WorkflowModels.StoredVersion version = workflowService.executable(tool.path("workflowCode").asText(), true);
            validateInputs(version.inputSchema(), object(arguments));
            String childRunId = UUID.randomUUID().toString();
            Long ownerId = TraceContextHolder.current().map(TraceContext::ownerUserId).orElseThrow();
            insertRun(childRunId, version, parentRunId, TraceContextHolder.currentTraceId().orElse(null), "AGENT", arguments, ownerId);
            markRunRunning(childRunId);
            try {
                JsonNode output = executeGraph(childRunId, version, object(arguments), depth + 1, stack, new AtomicInteger());
                jdbcTemplate.update("UPDATE workflow_run SET status='SUCCESS',output_encrypted=?,finished_at=NOW(),updated_at=NOW() WHERE id=?",
                    encrypt(output), childRunId);
                return output;
            } catch (RuntimeException exception) {
                jdbcTemplate.update("UPDATE workflow_run SET status='FAILED',error_message=?,finished_at=NOW(),updated_at=NOW() WHERE id=?",
                    truncate(exception.getMessage(), 2000), childRunId);
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
        requireRunAccess(run.ownerUserId());
        return new WorkflowModels.RunView(run.id(), run.workflowId(), run.workflowCode(), run.versionNumber(), run.parentRunId(),
            run.traceId(), run.triggerType(), run.status(), run.input(), run.output(), run.errorMessage(), run.ownerUserId(),
            run.cancelRequested(), run.startedAt(), run.finishedAt(), run.createdAt(), nodeRuns(runId));
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
        if (!Set.of("QUEUED", "RUNNING").contains(existing.status())) return existing;
        jdbcTemplate.update("""
            UPDATE workflow_run SET cancel_requested=true,status='CANCELLED',finished_at=NOW(),updated_at=NOW()
            WHERE id=? AND status IN ('QUEUED','RUNNING')
            """, runId);
        if (existing.traceId() != null) runtimeRegistry.cancel(existing.traceId());
        Future<?> future = futures.get(runId);
        if (future != null) future.cancel(true);
        if (existing.traceId() != null) taskTraceService.completeCancellation(existing.traceId());
        return run(runId);
    }

    /** 插入工作流运行记录。 */
    private void insertRun(String runId, WorkflowModels.StoredVersion version, String parentRunId, String traceId,
                           String triggerType, JsonNode inputs, Long ownerId) {
        jdbcTemplate.update("""
            INSERT INTO workflow_run(id,workflow_id,workflow_version_id,parent_run_id,trace_id,trigger_type,status,input_encrypted,output_encrypted,owner_user_id)
            VALUES (?,?,?,?,?,?,'QUEUED',?,'',?)
            """, runId, version.workflowId(), version.id(), parentRunId, traceId, triggerType, encrypt(inputs), ownerId);
    }

    /** 标记运行开始。 */
    private void markRunRunning(String runId) {
        jdbcTemplate.update("UPDATE workflow_run SET status='RUNNING',started_at=NOW(),updated_at=NOW() WHERE id=? AND status='QUEUED'", runId);
    }

    /** 标记运行取消。 */
    private void markCancelled(String runId) {
        jdbcTemplate.update("""
            UPDATE workflow_run SET status='CANCELLED',cancel_requested=true,finished_at=NOW(),updated_at=NOW()
            WHERE id=? AND status IN ('QUEUED','RUNNING','CANCELLED')
            """, runId);
    }

    /** 写入节点运行开始记录并返回主键。 */
    private long startNodeRun(String runId, JsonNode node, String type, int sequence, String iterationPath, JsonNode input) {
        Long id = insertKey("""
            INSERT INTO workflow_node_run(workflow_run_id,node_id,node_name,node_type,sequence_no,iteration_path,status,input_encrypted,output_encrypted)
            VALUES (?,?,?,?,?,?,'RUNNING',?,'')
            """, runId, node.path("id").asText(), node.path("data").path("label").asText(type), type,
            sequence, iterationPath, encrypt(input));
        return id == null ? 0 : id;
    }

    /** 写入节点终态、输出或错误。 */
    private void finishNodeRun(long id, String status, JsonNode output, String error) {
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
        return result;
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

    /** 校验运行记录只能由所属用户或管理员查看。 */
    private void requireRunAccess(Long ownerId) {
        AuthUser user = AuthContext.require();
        if (!user.roles().contains("ADMIN") && !user.id().equals(ownerId)) throw BusinessException.forbidden("workflow.runForbidden");
    }

    /** 查询并解密节点运行记录。 */
    private List<WorkflowModels.NodeRunView> nodeRuns(String runId) {
        return jdbcTemplate.query("SELECT * FROM workflow_node_run WHERE workflow_run_id=? ORDER BY sequence_no,id", (rs, row) ->
            new WorkflowModels.NodeRunView(rs.getLong("id"), rs.getString("node_id"), rs.getString("node_name"),
                rs.getString("node_type"), rs.getInt("sequence_no"), rs.getString("iteration_path"), rs.getString("status"),
                decrypt(rs.getString("input_encrypted")), decrypt(rs.getString("output_encrypted")), rs.getString("error_message"),
                timestamp(rs, "started_at"), timestamp(rs, "finished_at")), runId);
    }

    /** 映射并解密工作流运行记录。 */
    private WorkflowModels.RunView mapRun(ResultSet rs) throws SQLException {
        return new WorkflowModels.RunView(rs.getString("id"), rs.getLong("workflow_id"), rs.getString("workflow_code"),
            rs.getInt("version_number"), rs.getString("parent_run_id"), rs.getString("trace_id"), rs.getString("trigger_type"),
            rs.getString("status"), decrypt(rs.getString("input_encrypted")), decrypt(rs.getString("output_encrypted")),
            rs.getString("error_message"), rs.getLong("owner_user_id"), rs.getBoolean("cancel_requested"),
            timestamp(rs, "started_at"), timestamp(rs, "finished_at"), timestamp(rs, "created_at"), List.of());
    }

    /** 将工具参数规范为对象输入。 */
    private ObjectNode object(JsonNode value) {
        if (!value.isObject()) throw new BusinessException("workflow.agentArgumentsInvalid");
        return (ObjectNode) value.deepCopy();
    }

    /** 将节点自定义上限限制在平台硬上限内。 */
    private int bounded(int requested, int maximum) { return Math.max(1, Math.min(requested, Math.max(1, maximum))); }
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
    private record NodeResult(JsonNode output, Boolean branch) {}
}
