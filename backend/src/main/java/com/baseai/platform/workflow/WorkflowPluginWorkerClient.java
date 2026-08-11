package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** 调用 Base AI 自研插件 ABI Worker，不依赖 n8n 或 Dify 引擎与 SDK。 */
@Component
public class WorkflowPluginWorkerClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI difyWorker;
    private final URI n8nWorker;
    private final String token;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final WorkflowAdapterLifecycleService adapterLifecycleService;

    /** 创建只接受 HTTP(S) 内部地址并设置硬超时的 Worker 客户端。 */
    public WorkflowPluginWorkerClient(ObjectMapper objectMapper, PlatformProperties properties,
                                      WorkflowAdapterLifecycleService adapterLifecycleService) {
        this.objectMapper = objectMapper;
        this.adapterLifecycleService = adapterLifecycleService;
        PlatformProperties.Workflow workflow = properties.getWorkflow();
        difyWorker = workerUri(workflow.getDifyPluginWorkerUrl());
        n8nWorker = workerUri(workflow.getN8nPluginWorkerUrl());
        token = workflow.getPluginWorkerInternalToken() == null ? "" : workflow.getPluginWorkerInternalToken();
        timeout = Duration.ofSeconds(Math.max(1, Math.min(workflow.getPluginWorkerTimeoutSeconds(), 600)));
        maxResponseBytes = Math.max(1024, workflow.getMaxPayloadBytes() * 4);
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 把经过 Backend 校验的插件包发送给对应自研 ABI Worker 探测。 */
    public WorkerPackage inspect(String source, String packageId, String version, byte[] archive, String fingerprint) {
        ObjectNode body = objectMapper.createObjectNode().put("packageId", packageId).put("version", version)
            .put("fingerprint", fingerprint).put("archiveBase64", Base64.getEncoder().encodeToString(archive));
        JsonNode root = adapterLifecycleService.withEnabled(source,
            () -> post(worker(source).resolve("/packages/inspect"), body));
        if (!root.path("components").isArray() || !fingerprint.equalsIgnoreCase(root.path("fingerprint").asText())) {
            throw new BusinessException("workflow.pluginWorkerResponseInvalid");
        }
        int hostAbiVersion = root.path("hostAbiVersion").asInt(0);
        if (hostAbiVersion < expectedHostAbiVersion(source)) {
            throw new BusinessException("workflow.pluginWorkerResponseInvalid");
        }
        List<WorkerComponent> components = new ArrayList<>();
        for (JsonNode item : root.path("components")) {
            String externalId = text(item, "externalId");
            String componentType = text(item, "componentType").toUpperCase(Locale.ROOT);
            String status = text(item, "compatibilityStatus").toUpperCase(Locale.ROOT);
            if (externalId.isBlank() || !COMPONENT_TYPES.contains(componentType)
                || !List.of("SUPPORTED", "PARTIAL", "UNSUPPORTED").contains(status)) {
                throw new BusinessException("workflow.pluginWorkerResponseInvalid");
            }
            components.add(new WorkerComponent(externalId, text(item, "name"), text(item, "description"),
                componentType, item.path("schema").deepCopy(), item.path("credentialSchema").deepCopy(),
                text(item, "sourcePath"), status, text(item, "compatibilityReason")));
        }
        if (components.isEmpty()) throw new BusinessException("workflow.pluginWorkerResponseInvalid");
        return new WorkerPackage(source.toUpperCase(Locale.ROOT), packageId, version, fingerprint,
            text(root, "runtimeLanguage"), hostAbiVersion, List.copyOf(components));
    }

    /** 调用指定来源 Worker 中已固定版本的组件。 */
    public JsonNode invoke(String source, String fingerprint, String componentId, String operation,
                           JsonNode parameters, JsonNode credentials, JsonNode input, JsonNode context) {
        return invoke(source, fingerprint, componentId, operation, parameters, credentials, input, context, null);
    }

    /** 调用插件生命周期方法，并只附加后端生成的 OAuth 或事件字段。 */
    public JsonNode invoke(String source, String fingerprint, String componentId, String operation,
                           JsonNode parameters, JsonNode credentials, JsonNode input, JsonNode context,
                           ObjectNode lifecycle) {
        ObjectNode body = objectMapper.createObjectNode().put("fingerprint", fingerprint)
            .put("componentId", componentId).put("operation", operation);
        body.set("parameters", parameters == null ? objectMapper.createObjectNode() : parameters);
        body.set("credentials", credentials == null ? objectMapper.createObjectNode() : credentials);
        body.set("input", input == null ? objectMapper.nullNode() : input);
        body.set("context", context == null ? objectMapper.createObjectNode() : context);
        if (lifecycle != null) lifecycle.fields().forEachRemaining(entry -> body.set(entry.getKey(), entry.getValue()));
        JsonNode response = adapterLifecycleService.withEnabled(source,
            () -> post(worker(source).resolve("/invocations"), body));
        if (!response.path("success").asBoolean(false) || !response.has("output")) {
            throw new BusinessException("workflow.pluginExecutionFailed");
        }
        return response.path("output");
    }

    /** 删除数据库已确认未被安装记录引用的过期探测包。 */
    public void remove(String source, String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
            throw new BusinessException("workflow.pluginWorkerResponseInvalid");
        }
        ObjectNode body = objectMapper.createObjectNode().put("fingerprint", fingerprint);
        JsonNode response = adapterLifecycleService.withEnabled(source,
            () -> post(worker(source).resolve("/packages/remove"), body));
        if (!response.path("removed").asBoolean(false)) {
            throw new BusinessException("workflow.pluginWorkerResponseInvalid");
        }
    }

    private static final List<String> COMPONENT_TYPES = List.of(
        "ACTION", "TOOL", "TRIGGER", "MODEL", "DATASOURCE", "AGENT_STRATEGY", "EXTENSION");

    /** 发送受鉴权 JSON，并限制响应体积和错误细节。 */
    private JsonNode post(URI uri, JsonNode body) {
        if (token.length() < 24) throw new BusinessException("workflow.pluginWorkerUnavailable");
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                .header("X-Internal-Token", token).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(maxResponseBytes + 1);
                if (bytes.length > maxResponseBytes) throw new BusinessException("workflow.pluginWorkerResponseInvalid");
                JsonNode result = objectMapper.readTree(bytes);
                if (response.statusCode() / 100 != 2) {
                    String error = result.path("error").asText("PLUGIN_WORKER_FAILURE");
                    throw new BusinessException("workflow.pluginWorkerRejected", error.substring(0, Math.min(error.length(), 200)));
                }
                return result;
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("workflow.pluginWorkerUnavailable");
        } catch (Exception exception) {
            throw new BusinessException("workflow.pluginWorkerUnavailable");
        }
    }

    /** 选择插件来源对应的自研 Worker。 */
    private URI worker(String source) {
        return "DIFY".equalsIgnoreCase(source) ? difyWorker : "N8N".equalsIgnoreCase(source) ? n8nWorker
            : throwInvalidSource();
    }

    /** 生成表达式可用的非法来源异常。 */
    private URI throwInvalidSource() {
        throw new BusinessException("workflow.marketplaceSourceInvalid");
    }

    /** 验证 Worker 根地址不含凭据、查询和片段。 */
    private URI workerUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            String normalized = uri.toString().endsWith("/") ? uri.toString() : uri + "/";
            return URI.create(normalized);
        } catch (Exception exception) {
            throw new IllegalStateException("插件 Worker 地址无效", exception);
        }
    }

    /** 读取并清理 Worker 文本字段。 */
    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    /** 返回 Backend 当前能够解释的最低 Worker ABI 版本。 */
    public static int expectedHostAbiVersion(String source) {
        return "DIFY".equalsIgnoreCase(source) ? 4 : "N8N".equalsIgnoreCase(source) ? 3 : Integer.MAX_VALUE;
    }

    public record WorkerPackage(String source, String packageId, String version, String fingerprint,
                                String runtimeLanguage, int hostAbiVersion, List<WorkerComponent> components) {
        /** 兼容旧持久化结果；缺失版本会在再次浏览市场时触发重探测。 */
        public WorkerPackage(String source, String packageId, String version, String fingerprint,
                             String runtimeLanguage, List<WorkerComponent> components) {
            this(source, packageId, version, fingerprint, runtimeLanguage, 0, components);
        }
    }
    public record WorkerComponent(String externalId, String name, String description, String componentType,
                                  JsonNode schema, JsonNode credentialSchema, String sourcePath,
                                  String compatibilityStatus, String compatibilityReason) {}
}
