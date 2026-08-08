package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.LlmManagementService;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.trace.TraceContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 调用 Python Worker 执行一次模型工具选择，并纳入父任务追踪。 */
@Service
public class WorkflowAgentClient {
    private final RestClient restClient;
    private final LlmManagementService llmManagementService;
    private final TaskTraceService taskTraceService;
    private final ObjectMapper objectMapper;

    /** 注入 Worker、模型路由和追踪服务。 */
    public WorkflowAgentClient(@Qualifier("pythonWorkerRestClient") RestClient restClient,
                               LlmManagementService llmManagementService, TaskTraceService taskTraceService,
                               ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.llmManagementService = llmManagementService;
        this.taskTraceService = taskTraceService;
        this.objectMapper = objectMapper;
    }

    /** 根据节点模型配置请求一次 Agent 决策。 */
    public AgentStep step(JsonNode config, List<Map<String, Object>> messages, List<Tool> tools) {
        String featureCode = config.path("featureCode").asText("DEFAULT");
        Long modelId = config.hasNonNull("modelId") ? config.path("modelId").asLong() : null;
        String modelType = config.hasNonNull("modelType") ? config.path("modelType").asText() : modelId == null ? "text_model" : "";
        boolean thinking = config.path("enableThinking").asBoolean(false);
        String thinkingLevel = config.path("thinkingLevel").asText(null);
        LlmManagementService.WorkerRoute route = modelId == null
            ? llmManagementService.resolveActive(featureCode, modelType)
            : llmManagementService.resolveModel(modelId, modelType, thinking, thinkingLevel);
        String pythonTraceId = UUID.randomUUID().toString().replace("-", "");
        taskTraceService.registerPython(TraceContextHolder.currentTraceId().orElse(null), pythonTraceId, "/llm/agent-step");
        try {
            Map<String, Object> body = Map.of(
                "messages", messages,
                "tools", tools,
                "candidates", route.candidates(),
                "temperature", config.path("temperature").asDouble(0),
                "enableThinking", thinking
            );
            JsonNode response = restClient.post().uri("/llm/agent-step").header("X-Python-Trace-Id", pythonTraceId)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            if (response == null) throw new BusinessException("workflow.agentEmptyResponse");
            taskTraceService.updatePython(pythonTraceId, "SUCCESS", null, null);
            List<ToolCall> calls = new java.util.ArrayList<>();
            response.path("toolCalls").forEach(call -> calls.add(new ToolCall(call.path("id").asText(),
                call.path("name").asText(), parseArguments(call.path("arguments")))));
            return new AgentStep(response.path("content").asText(""), calls, response.path("model").asText(""));
        } catch (RestClientException exception) {
            taskTraceService.updatePython(pythonTraceId, "FAILED", null, exception.getMessage());
            throw new BusinessException(502, "workflow.agentCallFailed");
        } catch (RuntimeException exception) {
            taskTraceService.updatePython(pythonTraceId, "FAILED", null, exception.getMessage());
            throw exception;
        }
    }

    /** 兼容 Worker 返回的对象参数或 JSON 字符串参数。 */
    private JsonNode parseArguments(JsonNode value) {
        if (value.isObject()) return value;
        try { return objectMapper.readTree(value.asText("{}")); }
        catch (Exception exception) { throw new BusinessException("workflow.agentArgumentsInvalid"); }
    }

    public record Tool(String name, String description, JsonNode parameters) {}
    public record ToolCall(String id, String name, JsonNode arguments) {}
    public record AgentStep(String content, List<ToolCall> toolCalls, String model) {}
}
