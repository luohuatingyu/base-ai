package com.baseai.platform.workflow;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** 管理插件 OAuth 的一次性 state、PKCE verifier 和加密凭据落库。 */
@Service
public class WorkflowPluginOAuthService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final WorkflowConnectionService connections;
    private final WorkflowPluginRegistryService registry;
    private final WorkflowPluginWorkerClient workers;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 注入持久化、加密、连接、插件注册表和 Worker 客户端。 */
    public WorkflowPluginOAuthService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                      ObjectMapper objectMapper, ConfigCryptoService cryptoService,
                                      WorkflowConnectionService connections, WorkflowPluginRegistryService registry,
                                      WorkflowPluginWorkerClient workers) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.connections = connections;
        this.registry = registry;
        this.workers = workers;
    }

    /** 为当前用户拥有的插件连接创建十分钟有效的一次性授权请求。 */
    @Transactional
    public WorkflowModels.PluginOAuthAuthorization authorize(Long connectionId,
                                                              WorkflowModels.PluginOAuthAuthorizeCommand command) {
        String redirectUri = safeUri(command == null ? null : command.redirectUri(), true);
        WorkflowConnectionService.StoredConnection connection = connections.ownedForTest(connectionId);
        if (!"PLUGIN".equals(connection.connectionType())) throw new BusinessException("workflow.pluginOAuthInvalid");
        Long componentId = connection.config().path("pluginComponentId").asLong();
        WorkflowPluginRegistryService.RuntimeComponent component = registry.requireRuntimeComponent(componentId);
        String state = token(32);
        String verifier = token(48);
        ObjectNode lifecycle = objectMapper.createObjectNode().put("redirectUri", redirectUri)
            .put("state", state).put("codeVerifier", verifier);
        JsonNode output = workers.invoke(component.source(), component.packageFingerprint(), component.externalKey(),
            "oauth_authorize", objectMapper.createObjectNode(), credentials(connection), objectMapper.nullNode(),
            objectMapper.createObjectNode(), lifecycle, component.allowedDomains());
        String authorizationUrl = authorizationUrl(output, state);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        jdbcTemplate.update("""
            INSERT INTO workflow_plugin_oauth_state(state_hash,connection_id,component_id,verifier_encrypted,redirect_uri,expires_at)
            VALUES (?,?,?,?,?,?)
            """, sha256(state), connectionId, componentId, cryptoService.encrypt(verifier), redirectUri, expiresAt);
        return new WorkflowModels.PluginOAuthAuthorization(authorizationUrl, state, expiresAt);
    }

    /** 校验并消费一次性 state，通过插件交换授权码后替换加密凭据。 */
    @Transactional
    public WorkflowModels.PluginOAuthCallbackResult callback(WorkflowModels.PluginOAuthCallbackCommand command) {
        String state = command == null ? "" : text(command.state(), 256);
        String code = command == null ? "" : text(command.code(), 4096);
        if (!state.matches("[A-Za-z0-9_-]{40,256}") || code.isBlank()) {
            throw new BusinessException("workflow.pluginOAuthInvalid");
        }
        List<OAuthState> rows = jdbcTemplate.query("""
            SELECT connection_id,component_id,verifier_encrypted,redirect_uri FROM workflow_plugin_oauth_state
            WHERE state_hash=? AND consumed_at IS NULL AND expires_at>NOW() FOR UPDATE
            """, (rs, row) -> new OAuthState(rs.getLong("connection_id"), rs.getLong("component_id"),
            rs.getString("verifier_encrypted"), rs.getString("redirect_uri")), sha256(state));
        if (rows.isEmpty()) throw new BusinessException("workflow.pluginOAuthStateInvalid");
        OAuthState oauth = rows.get(0);
        WorkflowConnectionService.StoredConnection connection = connections.ownedForTest(oauth.connectionId());
        if (!"PLUGIN".equals(connection.connectionType())
            || connection.config().path("pluginComponentId").asLong() != oauth.componentId()) {
            throw new BusinessException("workflow.pluginOAuthInvalid");
        }
        WorkflowPluginRegistryService.RuntimeComponent component = registry.requireRuntimeComponent(oauth.componentId());
        ObjectNode lifecycle = objectMapper.createObjectNode().put("redirectUri", oauth.redirectUri())
            .put("state", state).put("code", code).put("codeVerifier", cryptoService.decrypt(oauth.verifierEncrypted()));
        JsonNode output = workers.invoke(component.source(), component.packageFingerprint(), component.externalKey(),
            "oauth_exchange", objectMapper.createObjectNode(), credentials(connection), objectMapper.nullNode(),
            objectMapper.createObjectNode(), lifecycle, component.allowedDomains());
        JsonNode newCredentials = output.path("credentials").isObject() ? output.path("credentials") : output;
        if (!newCredentials.isObject()) throw new BusinessException("workflow.pluginOAuthResponseInvalid");
        connections.replacePluginCredentials(oauth.connectionId(), oauth.componentId(), newCredentials);
        jdbcTemplate.update("UPDATE workflow_plugin_oauth_state SET consumed_at=NOW() WHERE state_hash=?", sha256(state));
        return new WorkflowModels.PluginOAuthCallbackResult(oauth.connectionId(), true);
    }

    /** 读取插件连接中的明文 credentials 子对象。 */
    private JsonNode credentials(WorkflowConnectionService.StoredConnection connection) {
        JsonNode credentials = connection.config().path("credentials");
        return credentials.isObject() ? credentials : objectMapper.createObjectNode();
    }

    /** 提取并验证插件返回的授权 URL，缺少 state 时由宿主安全追加。 */
    private String authorizationUrl(JsonNode output, String state) {
        String value = output.isTextual() ? output.asText() : output.path("authorizationUrl").asText("");
        if (value.isBlank()) value = output.path("url").asText("");
        if (value.isBlank() && output.isArray() && !output.isEmpty()) value = output.get(0).path("value").asText("");
        String url = safeUri(value, false);
        String encoded = URLEncoder.encode(state, StandardCharsets.UTF_8);
        String query = URI.create(url).getRawQuery();
        List<String> states = query == null ? List.of() : java.util.Arrays.stream(query.split("&"))
            .filter(item -> item.startsWith("state="))
            .map(item -> URLDecoder.decode(item.substring(6), StandardCharsets.UTF_8)).toList();
        if (!states.isEmpty() && states.stream().noneMatch(state::equals)) {
            throw new BusinessException("workflow.pluginOAuthInvalid");
        }
        if (states.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + "state=" + encoded;
        }
        return url;
    }

    /** 只允许 Web OAuth 地址，并限制长度、凭据和片段。 */
    private String safeUri(String value, boolean allowLocalHttp) {
        try {
            if (value == null || value.length() > 1000) throw new IllegalArgumentException();
            String text = value.trim();
            URI uri = URI.create(text);
            boolean localHttp = allowLocalHttp && "http".equalsIgnoreCase(uri.getScheme())
                && List.of("localhost", "127.0.0.1", "::1").contains(uri.getHost());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || localHttp) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri.toString();
        } catch (Exception exception) {
            throw new BusinessException("workflow.pluginOAuthInvalid");
        }
    }

    /** 生成 URL 安全的高熵随机值。 */
    private String token(int bytes) {
        byte[] value = new byte[bytes];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** 计算 state 的不可逆数据库索引。 */
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    /** 规范外部文本并限制长度。 */
    private String text(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) throw new BusinessException("workflow.pluginOAuthInvalid");
        return normalized;
    }

    private record OAuthState(Long connectionId, Long componentId, String verifierEncrypted, String redirectUri) {}
}
