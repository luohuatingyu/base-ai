package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** 通过受鉴权内部协议控制固定的 n8n 与 Dify Worker 容器。 */
@Component
public class WorkflowAdapterManagerClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI manager;
    private final String token;

    /** 创建仅接受 HTTP(S) 内部地址的短超时控制客户端。 */
    public WorkflowAdapterManagerClient(ObjectMapper objectMapper, PlatformProperties properties) {
        this.objectMapper = objectMapper;
        PlatformProperties.Workflow workflow = properties.getWorkflow();
        manager = managerUri(workflow.getAdapterManagerUrl());
        token = workflow.getAdapterManagerInternalToken() == null ? "" : workflow.getAdapterManagerInternalToken();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 查询指定来源对应容器的实际状态。 */
    public ManagerState state(String source) { return request(source, null); }

    /** 提交指定来源的异步启动或停止命令。 */
    public ManagerState setEnabled(String source, boolean enabled) {
        return request(source, objectMapper.createObjectNode().put("enabled", enabled).toString());
    }

    /** 发送受鉴权控制请求并限制响应体与错误信息。 */
    private ManagerState request(String source, String body) {
        String normalizedSource = WorkflowAdapterLifecycleService.source(source);
        if (token.length() < 24) throw new BusinessException("workflow.adapterManagerUnavailable");
        try {
            URI uri = manager.resolve("api/adapters/" + normalizedSource);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("X-Internal-Token", token).header("Accept", "application/json");
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(4097);
                if (bytes.length > 4096) throw new BusinessException("workflow.adapterManagerResponseInvalid");
                JsonNode root = objectMapper.readTree(bytes);
                if (response.statusCode() / 100 != 2 || !normalizedSource.equals(root.path("source").asText())) {
                    throw new BusinessException("workflow.adapterManagerRejected");
                }
                String status = root.path("status").asText("").toUpperCase();
                if (!List.of("ENABLING", "RUNNING", "STARTING", "DISABLING", "STOPPED", "FAILED").contains(status)) {
                    throw new BusinessException("workflow.adapterManagerResponseInvalid");
                }
                return new ManagerState(normalizedSource, status, safeError(root.path("error").asText("")));
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("workflow.adapterManagerUnavailable");
        } catch (Exception exception) {
            throw new BusinessException("workflow.adapterManagerUnavailable");
        }
    }

    /** 验证 manager 根地址不包含凭据、查询参数或片段。 */
    private URI managerUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return URI.create(uri.toString().endsWith("/") ? uri.toString() : uri + "/");
        } catch (Exception exception) {
            throw new IllegalStateException("适配器控制服务地址无效", exception);
        }
    }

    /** 仅保留 manager 返回的有限错误码，禁止透传命令输出。 */
    private String safeError(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("[A-Z0-9_]{1,80}") ? normalized : "";
    }

    /** 隔离控制服务返回的实际容器状态。 */
    public record ManagerState(String source, String status, String error) {}
}
