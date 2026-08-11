package com.baseai.platform.workflow;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowPluginOAuthServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private WorkflowConnectionService connections;
    private WorkflowPluginRegistryService registry;
    private WorkflowPluginWorkerClient workers;
    private WorkflowPluginOAuthService service;

    /** 创建一次性 OAuth 状态表和固定测试密钥。 */
    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-plugin-oauth-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_plugin_oauth_state(state_hash CHAR(64) PRIMARY KEY,connection_id BIGINT,component_id BIGINT,
            verifier_encrypted CLOB,redirect_uri VARCHAR(500),expires_at TIMESTAMP,consumed_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        connections = mock(WorkflowConnectionService.class);
        registry = mock(WorkflowPluginRegistryService.class);
        workers = mock(WorkflowPluginWorkerClient.class);
        service = new WorkflowPluginOAuthService(jdbcTemplate, mapper, new ConfigCryptoService(properties),
            connections, registry, workers);
        var connection = new WorkflowConnectionService.StoredConnection(9L, "PLUGIN", "Plugin", "PLUGIN",
            mapper.readTree("{\"pluginComponentId\":7,\"credentials\":{\"clientId\":\"id\"}}"),
            3L, true, null, null);
        when(connections.ownedForTest(9L)).thenReturn(connection);
        when(registry.requireRuntimeComponent(7L)).thenReturn(new WorkflowPluginRegistryService.RuntimeComponent(
            7L, "DIFY", "pkg", "1", "a".repeat(64), true, "oauth", "EXTENSION",
            mapper.createArrayNode(), mapper.createArrayNode(), "SUPPORTED"));
    }

    /** state 和 verifier 必须高熵、不可明文落库且授权 URL 自动携带 state。 */
    @Test
    void createsEncryptedOneTimeAuthorizationState() {
        when(workers.invoke(anyString(), anyString(), anyString(), anyString(), any(JsonNode.class),
            any(JsonNode.class), any(JsonNode.class), any(JsonNode.class), any(ObjectNode.class)))
            .thenReturn(mapper.createObjectNode().put("authorizationUrl", "https://accounts.example.com/authorize?client_id=id"));

        var result = service.authorize(9L,
            new WorkflowModels.PluginOAuthAuthorizeCommand("https://base.example.com/oauth/callback"));

        assertFalse(result.state().isBlank());
        assertEquals(true, result.authorizationUrl().contains("state="));
        String encrypted = jdbcTemplate.queryForObject("SELECT verifier_encrypted FROM workflow_plugin_oauth_state", String.class);
        assertEquals(true, encrypted.startsWith("enc:"));
        assertFalse(encrypted.contains(result.state()));
    }

    /** 回调只允许消费一次，并把 Worker 返回的对象交给连接加密服务。 */
    @Test
    void exchangesCodeOnceAndReplacesCredentials() {
        when(workers.invoke(anyString(), anyString(), anyString(), anyString(), any(JsonNode.class),
            any(JsonNode.class), any(JsonNode.class), any(JsonNode.class), any(ObjectNode.class)))
            .thenReturn(mapper.createObjectNode().put("authorizationUrl", "https://accounts.example.com/authorize"))
            .thenReturn(mapper.createObjectNode().putObject("credentials").put("accessToken", "secret"));
        var authorization = service.authorize(9L,
            new WorkflowModels.PluginOAuthAuthorizeCommand("https://base.example.com/oauth/callback"));

        var result = service.callback(new WorkflowModels.PluginOAuthCallbackCommand(authorization.state(), "code"));

        assertEquals(9L, result.connectionId());
        verify(connections).replacePluginCredentials(9L, 7L,
            mapper.createObjectNode().put("accessToken", "secret"));
        assertEquals("workflow.pluginOAuthStateInvalid", assertThrows(BusinessException.class,
            () -> service.callback(new WorkflowModels.PluginOAuthCallbackCommand(authorization.state(), "code"))).getMessageKey());
    }

    /** 非 HTTPS 授权回调必须在调用插件前拒绝。 */
    @Test
    void rejectsUnsafeRedirectUri() {
        assertEquals("workflow.pluginOAuthInvalid", assertThrows(BusinessException.class,
            () -> service.authorize(9L, new WorkflowModels.PluginOAuthAuthorizeCommand(
                "http://attacker.example.com/callback"))).getMessageKey());
    }

    /** 插件不得覆盖宿主生成的 OAuth state，否则必须拒绝授权 URL。 */
    @Test
    void rejectsPluginAuthorizationUrlWithForgedState() {
        when(workers.invoke(anyString(), anyString(), anyString(), anyString(), any(JsonNode.class),
            any(JsonNode.class), any(JsonNode.class), any(JsonNode.class), any(ObjectNode.class)))
            .thenReturn(mapper.createObjectNode().put("authorizationUrl",
                "https://accounts.example.com/authorize?state=forged"));

        assertEquals("workflow.pluginOAuthInvalid", assertThrows(BusinessException.class,
            () -> service.authorize(9L, new WorkflowModels.PluginOAuthAuthorizeCommand(
                "https://base.example.com/oauth/callback"))).getMessageKey());
    }
}
