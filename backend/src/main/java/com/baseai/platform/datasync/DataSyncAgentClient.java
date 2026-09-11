package com.baseai.platform.datasync;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.deployment.ServerModels;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 通过隔离 Deployment Agent 在所选服务器运行一次性数据同步 Worker。 */
@Component
public class DataSyncAgentClient {
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String agentUrl;
    private final String agentToken;
    private final String workerImage;

    /** 注入 Agent 地址、内部令牌和固定 Worker 镜像。 */
    public DataSyncAgentClient(ObjectMapper objectMapper,
                               @Value("${app.deployment-agent.url:}") String agentUrl,
                               @Value("${app.deployment-agent.internal-token:}") String agentToken,
                               @Value("${app.data-sync.worker-image:}") String workerImage) {
        this.objectMapper = objectMapper;
        this.agentUrl = text(agentUrl).replaceFirst("/+$", "");
        this.agentToken = text(agentToken);
        this.workerImage = text(workerImage);
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(16));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 在执行服务器查询数据库表元数据。 */
    public List<DataSyncModels.TableView> tables(ServerModels.DataSyncExecutionTarget server,
                                                  WorkflowConnectionService.StoredConnection connection,
                                                  String schema) {
        JsonNode response = query(server, task("TABLES", connection, null, null, schema, List.of()));
        if (!"SUCCEEDED".equals(response.path("status").asText()) || !response.path("tables").isArray()) {
            throw new BusinessException("dataSync.agentInvalidResponse");
        }
        return objectMapper.convertValue(response.path("tables"),
            objectMapper.getTypeFactory().constructCollectionType(List.class, DataSyncModels.TableView.class));
    }

    /** 在执行服务器完成源目标结构预检。 */
    public DataSyncModels.PreviewView preview(ServerModels.DataSyncExecutionTarget server,
                                              WorkflowConnectionService.StoredConnection source,
                                              WorkflowConnectionService.StoredConnection target,
                                              String strategy, List<DataSyncModels.TableMapping> tables) {
        JsonNode response = query(server, task("PREVIEW", source, target, strategy, "", tables));
        if (!"SUCCEEDED".equals(response.path("status").asText()) || !response.path("preview").isObject()) {
            throw new BusinessException("dataSync.agentInvalidResponse");
        }
        return objectMapper.convertValue(response.path("preview"), DataSyncModels.PreviewView.class);
    }

    /** 以已持久化的幂等任务编号启动远程同步。 */
    public void start(ServerModels.DataSyncExecutionTarget server, String jobId,
                      WorkflowConnectionService.StoredConnection source,
                      WorkflowConnectionService.StoredConnection target,
                      String strategy, List<DataSyncModels.TableMapping> tables) {
        JsonNode response = post("/data-sync/execute", payload(server, jobId,
            task("RUN", source, target, strategy, "", tables)));
        if (!jobId.equals(response.path("jobId").asText())
            || !List.of("RUNNING", "SUCCEEDED").contains(response.path("status").asText())) {
            throw new BusinessException("dataSync.agentInvalidResponse");
        }
    }

    /** 查询远程任务状态和最近一次结构化进度。 */
    public AgentJob job(String jobId) {
        JsonNode response = get("/data-sync/jobs/" + jobId);
        String status = response.path("status").asText();
        if (!jobId.equals(response.path("jobId").asText())
            || !List.of("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
            throw new BusinessException("dataSync.agentInvalidResponse");
        }
        JsonNode result = response.path("result");
        return new AgentJob(status, result.isObject() ? result : null, safe(response.path("error").asText()));
    }

    /** 请求 Agent 终止对应的一次性 Worker 容器。 */
    public void cancel(String jobId) {
        JsonNode response = post("/data-sync/jobs/" + jobId + "/cancel", Map.of());
        if (!jobId.equals(response.path("jobId").asText())
            || !List.of("CANCELLED", "SUCCEEDED", "FAILED").contains(response.path("status").asText())) {
            throw new BusinessException("dataSync.agentInvalidResponse");
        }
    }

    /** 执行同步查询并统一处理 Agent 错误。 */
    private JsonNode query(ServerModels.DataSyncExecutionTarget server, Map<String, Object> task) {
        JsonNode response = post("/data-sync/query", payload(server, "", task));
        if ("FAILED".equals(response.path("status").asText())) throw new BusinessException("dataSync.executionFailed");
        return response;
    }

    /** 组装只在 Backend 与内部 Agent 间传递的受管服务器配置。 */
    private Map<String, Object> payload(ServerModels.DataSyncExecutionTarget server, String jobId,
                                        Map<String, Object> task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", server.mode());
        payload.put("host", server.host());
        payload.put("port", server.port());
        payload.put("username", server.username());
        payload.put("authType", server.authType());
        payload.put("privateKey", server.privateKey());
        payload.put("password", server.password());
        payload.put("passphrase", server.passphrase());
        payload.put("hostKey", server.hostKey());
        payload.put("workerImage", workerImage);
        payload.put("jobId", jobId);
        payload.put("task", task);
        return payload;
    }

    /** 组装不包含平台内部字段的数据同步 Worker 输入。 */
    private Map<String, Object> task(String action, WorkflowConnectionService.StoredConnection source,
                                     WorkflowConnectionService.StoredConnection target, String strategy,
                                     String schema, List<DataSyncModels.TableMapping> tables) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("action", action);
        task.put("source", connection(source));
        if (target != null) task.put("target", connection(target));
        task.put("strategy", strategy == null ? "" : strategy);
        task.put("schema", schema == null ? "" : schema);
        task.put("tables", tables);
        return task;
    }

    /** 仅向一次性 Worker 发送建立 JDBC 连接所需字段。 */
    private Map<String, Object> connection(WorkflowConnectionService.StoredConnection connection) {
        JsonNode config = connection.config();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", connection.connectionType());
        result.put("url", config.path("url").asText(""));
        result.put("username", config.path("username").asText(""));
        result.put("password", config.path("password").asText(""));
        result.put("allowWrite", config.path("allowWrite").asBoolean(false));
        return result;
    }

    /** 发送内部 POST 请求并隐藏网络实现细节。 */
    private JsonNode post(String path, Object body) {
        ensureConfigured();
        try {
            JsonNode response = restClient.post().uri(agentUrl + path)
                .header("Authorization", "Bearer " + agentToken).body(body).retrieve().body(JsonNode.class);
            if (response == null) throw new BusinessException("dataSync.agentInvalidResponse");
            return response;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException("dataSync.agentUnavailable");
        }
    }

    /** 发送内部 GET 请求并隐藏网络实现细节。 */
    private JsonNode get(String path) {
        ensureConfigured();
        try {
            JsonNode response = restClient.get().uri(agentUrl + path)
                .header("Authorization", "Bearer " + agentToken).retrieve().body(JsonNode.class);
            if (response == null) throw new BusinessException("dataSync.agentInvalidResponse");
            return response;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException("dataSync.agentUnavailable");
        }
    }

    /** 确认远程执行基础配置完整。 */
    private void ensureConfigured() {
        if (agentUrl.isBlank() || agentToken.length() < 24 || workerImage.isBlank()) {
            throw new BusinessException("dataSync.agentUnavailable");
        }
    }

    /** 限制 Agent 错误长度并隐藏常见敏感字段。 */
    private String safe(String value) {
        String normalized = text(value).replaceAll("(?i)(password|secret|token)(?:=|\\\"\\s*:\\s*\\\")[^\\s,}\\\"]+", "$1=******");
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    /** 统一空文本处理。 */
    private String text(String value) { return value == null ? "" : value.trim(); }

    record AgentJob(String status, JsonNode result, String error) { }
}
