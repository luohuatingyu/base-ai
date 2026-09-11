package com.baseai.platform.deployment;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.service.TaskTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实加密和 H2 数据库验证凭据保存、共享引用和安全边界。 */
class ServerCredentialServiceTest {
    private JdbcTemplate jdbc;
    private ConfigCryptoService crypto;
    private ServerCredentialService credentials;
    private ServerManagementService servers;

    /** 初始化独立数据库并执行实际凭据迁移。 */
    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = spy(new JdbcTemplate(source));
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO sys_user VALUES(7),(8)");
        jdbc.execute("""
            CREATE TABLE managed_server(id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(120), mode VARCHAR(16),
                host VARCHAR(255), port INT, username VARCHAR(64), config_encrypted TEXT, owner_user_id BIGINT,
                enabled BOOLEAN, voided BOOLEAN DEFAULT FALSE, last_test_status VARCHAR(32), last_test_error TEXT,
                last_test_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/mysql/V35__add_server_credentials.sql"),
            new ClassPathResource("db/migration/mysql/V36__add_server_credential_passphrase.sql")).execute(source);
        doAnswer(invocation -> jdbc.queryForObject("SELECT MAX(id) FROM managed_server", Long.class))
            .when(jdbc).queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        crypto = new ConfigCryptoService(properties);
        credentials = new ServerCredentialService(jdbc, crypto);
        servers = new ServerManagementService(jdbc, new ObjectMapper(), crypto, mock(TaskTraceService.class), mock(ThreadPoolTaskExecutor.class), "", "");
        authenticate(7L, false);
    }

    /** 清除请求身份，防止影响其他用例。 */
    @AfterEach
    void cleanup() { AuthContext.clear(); }

    /** 分别保存两类材料，脱敏视图不泄密，空更新保留原始秘密。 */
    @Test
    void storesEncryptedMaterialsAndPreservesSecretsOnEdit() throws Exception {
        var created = credentials.save(null, command("label", "private", "", "phrase"));
        var account = credentials.save(null, command("account", "", " password ", ""));
        assertTrue(created.hasPrivateKey());
        assertFalse(created.hasPassword());
        assertTrue(created.hasPassphrase());
        assertEquals("", created.publicKey());
        assertEquals("", created.certificate());
        String encrypted = jdbc.queryForObject("SELECT password_encrypted FROM server_credential WHERE id=?", String.class, account.id());
        assertTrue(encrypted.startsWith("enc:v1:"));
        assertEquals(" password ", crypto.decrypt(encrypted));
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(created).contains(" password "));
        credentials.save(created.id(), new CredentialModels.Command("changed", "KEY", "deploy", "", "", "", "", true, ""));
        credentials.save(account.id(), command("changed", "", "******", ""));
        assertEquals("private", credentials.resolve(created.id(), 7L, false).privateKey());
        assertEquals(" password ", credentials.resolve(account.id(), 7L, false).password());
        assertEquals("phrase", credentials.resolve(created.id(), 7L, false).passphrase());
    }

    /** 多台服务器共享引用，轮换后数据同步读取新密码而服务器不保存副本。 */
    @Test
    void sharesCredentialsAndResolvesLatestValues() throws Exception {
        var credential = credentials.save(null, command("shared", "", "first", ""));
        var first = servers.create(server(credential.id(), "PASSWORD"));
        var second = servers.create(server(credential.id(), "PASSWORD"));
        assertEquals(credential.id(), first.credentialId());
        assertEquals("deploy", first.username());
        var config = new ObjectMapper().readTree(crypto.decrypt(jdbc.queryForObject("SELECT config_encrypted FROM managed_server WHERE id=?", String.class, first.id())));
        assertEquals("", config.path("privateKey").asText());
        assertEquals("", config.path("password").asText());
        credentials.save(credential.id(), command("rotated", "", "second", ""));
        assertEquals("second", servers.requireDataSyncTarget(first.id(), 7L).password());
        assertEquals("second", servers.requireDataSyncTarget(second.id(), 7L).password());
        assertEquals("", servers.requireDataSyncTarget(first.id(), 7L).privateKey());
        assertEquals("", servers.requireDataSyncTarget(first.id(), 7L).passphrase());
        assertEquals(409, assertThrows(BusinessException.class, () -> credentials.delete(credential.id())).getStatus());
        var disabled = new CredentialModels.Command("shared", "PASSWORD", "deploy", "", "", "", "", false, "");
        assertEquals(409, assertThrows(BusinessException.class, () -> credentials.save(credential.id(), disabled)).getStatus());
    }

    /** 普通用户无法跨所有者读取、修改、删除或绑定，也不能查看明文。 */
    @Test
    void enforcesOwnershipAndAdminSecretAccess() {
        var credential = credentials.save(null, command("owned", "", "secret", ""));
        assertEquals(403, assertThrows(BusinessException.class, () -> credentials.reveal(credential.id())).getStatus());
        authenticate(8L, false);
        assertTrue(credentials.list().isEmpty());
        assertEquals(403, assertThrows(BusinessException.class, () -> credentials.save(credential.id(), command("bad", "", "", ""))).getStatus());
        assertEquals(403, assertThrows(BusinessException.class, () -> credentials.delete(credential.id())).getStatus());
        assertEquals(403, assertThrows(BusinessException.class, () -> servers.create(server(credential.id(), "KEY"))).getStatus());
        authenticate(8L, true);
        assertEquals(1, credentials.list().size());
        assertEquals("secret", credentials.reveal(credential.id()).password());
        assertEquals(403, assertThrows(BusinessException.class, () -> servers.create(server(credential.id(), "KEY"))).getStatus());
        AuthContext.clear();
        assertEquals(401, assertThrows(BusinessException.class, () -> credentials.list()).getStatus());
    }

    /** 拒绝不支持的类型，不可把其他算法静默保存为 RSA。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "EC", "ED25519", "rsa"})
    void rejectsUnsupportedType(String type) {
        var command = new CredentialModels.Command("label", type, "deploy", "", "", "", "secret", true, "");
        assertEquals("server.credentialTypeInvalid", assertThrows(BusinessException.class, () -> credentials.save(null, command)).getMessageKey());
        assertTrue(credentials.list().isEmpty());
    }

    /** 空内容、超限文本及恶意账号均不产生数据。 */
    @ParameterizedTest
    @ValueSource(strings = {"label", "material", "password", "username", "empty"})
    void rejectsInvalidFields(String field) {
        var command = new CredentialModels.Command(field.equals("label") ? "a".repeat(121) : "label", "PASSWORD",
            field.equals("username") ? "root;id" : "deploy", field.equals("material") ? "密".repeat(11000) : "",
            "", "", field.equals("empty") ? "" : field.equals("password") ? "a".repeat(1025) : "secret", true, "");
        assertThrows(BusinessException.class, () -> credentials.save(null, command));
        assertTrue(credentials.list().isEmpty());
    }

    /** 停用、删除、不存在及缺少认证材料的凭据均不能绑定服务器。 */
    @Test
    void rejectsUnavailableCredentialsAndSupportsAccountOnly() {
        var account = credentials.save(null, new CredentialModels.Command("account", "PASSWORD", "deploy", "", "", "", "secret", true, ""));
        assertFalse(account.hasPrivateKey());
        assertThrows(BusinessException.class, () -> servers.create(server(account.id(), "KEY")));
        credentials.save(account.id(), new CredentialModels.Command("account", "PASSWORD", "deploy", "", "", "", "", false, ""));
        assertEquals("server.credentialDisabled", assertThrows(BusinessException.class, () -> servers.create(server(account.id(), "PASSWORD"))).getMessageKey());
        credentials.delete(account.id());
        assertEquals(404, assertThrows(BusinessException.class, () -> credentials.resolve(999L, 7L, false)).getStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> credentials.resolve(account.id(), 7L, false)).getStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> credentials.save(999L, command("missing", "", "secret", ""))).getStatus());
    }

    /** 从历史直接凭据切换引用，并支持切回本地解除引用。 */
    @Test
    void updatesLegacyServerAndUnlinksLocalMode() {
        var legacy = servers.create(new ServerModels.ServerCommand("legacy", "SSH", "host", 22, "deploy", "PASSWORD", "", "legacy-secret", "", "", "", "", true));
        var credential = credentials.save(null, command("shared", "", "new-secret", ""));
        servers.update(legacy.id(), server(credential.id(), "PASSWORD"));
        assertEquals("new-secret", servers.requireDataSyncTarget(legacy.id(), 7L).password());
        servers.update(legacy.id(), new ServerModels.ServerCommand("local", "LOCAL", "", null, "", "", "", "", "", "", "", "", true));
        assertNull(jdbc.queryForObject("SELECT credential_id FROM managed_server WHERE id=?", Long.class, legacy.id()));
        credentials.delete(credential.id());
        assertTrue(credentials.list().isEmpty());
    }

    /** 通过真实 HTTP 隔离 Agent 接收轮换后的秘密，外部响应不包含认证材料。 */
    @Test
    void passesRotatedCredentialsToAgentWithoutExposingThem() throws Exception {
        var received = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var agent = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/", exchange -> {
            received.add(new ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] body = "{\"status\":\"SUCCEEDED\",\"output\":\"potential-secret\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        agent.start();
        try {
            var connected = new ServerManagementService(jdbc, new ObjectMapper(), crypto, mock(TaskTraceService.class),
                mock(ThreadPoolTaskExecutor.class), "http://127.0.0.1:" + agent.getAddress().getPort(), "internal-test-token");
            var credential = credentials.save(null, command("shared", "first-private", "", "phrase"));
            var server = connected.create(server(credential.id(), "KEY"));
            credentials.save(credential.id(), command("shared", "rotated-private", "", ""));
            assertEquals("CONNECTION_SUCCEEDED", connected.test(server.id()).get("output"));
            connected.monitor(server.id());
            assertEquals(2, received.size());
            for (var payload : received) {
                assertEquals("deploy", payload.path("username").asText());
                assertEquals("rotated-private", payload.path("privateKey").asText());
                assertEquals("", payload.path("password").asText());
                assertEquals("phrase", payload.path("passphrase").asText());
            }
            String external = new ObjectMapper().findAndRegisterModules().writeValueAsString(connected.servers());
            assertFalse(external.contains("rotated-"));
        } finally { agent.stop(0); }
    }

    /** 边界长度可保存，多次新建返回自己的记录，损坏密文只返回稳定错误。 */
    @Test
    void supportsBoundariesAndReportsUnreadableSecret() {
        var maximum = credentials.save(null, new CredentialModels.Command("a".repeat(120), "KEY", "deploy", "p".repeat(32768),
            "k".repeat(32768), "c".repeat(32768), "", true, "s".repeat(1024)));
        var next = credentials.save(null, command("next", "", "secret", ""));
        assertNotEquals(maximum.id(), next.id());
        assertEquals("next", next.label());
        assertEquals(32768, maximum.publicKey().length());
        jdbc.update("UPDATE server_credential SET password_encrypted='broken' WHERE id=?", next.id());
        assertEquals("server.credentialUnreadable", assertThrows(BusinessException.class,
            () -> credentials.resolve(next.id(), 7L, false)).getMessageKey());
    }

    /** 绑定不同凭据、切换认证方式以及无账号的私钥均按服务器设置工作。 */
    @Test
    void switchesReferencesAndUsesServerUsernameForKeyOnly() {
        var key = credentials.save(null, new CredentialModels.Command("key", "KEY", "", "", "private", "", "", true, ""));
        var command = new ServerModels.ServerCommand("server", "SSH", "host", 22, "deploy", "KEY", "", "", "", "", "", "", true, key.id());
        var server = servers.create(command);
        assertEquals("deploy", servers.requireDataSyncTarget(server.id(), 7L).username());
        var account = credentials.save(null, command("account", "", "password", ""));
        servers.update(server.id(), server(account.id(), "PASSWORD"));
        credentials.delete(key.id());
        assertEquals(account.id(), servers.servers().get(0).credentialId());
        assertEquals("password", servers.requireDataSyncTarget(server.id(), 7L).password());
        jdbc.update("UPDATE server_credential SET enabled=false WHERE id=?", account.id());
        assertEquals("server.credentialDisabled", assertThrows(BusinessException.class,
            () -> servers.requireDataSyncTarget(server.id(), 7L)).getMessageKey());
    }

    /** 切换类型必须持久化且清除旧秘密，避免产生隐式组合认证。 */
    @Test
    void switchesTypeAndClearsOldSecrets() {
        var account = credentials.save(null, new CredentialModels.Command("account", "PASSWORD", "deploy", "", "", "", "secret", true, ""));
        var key = credentials.save(account.id(), new CredentialModels.Command("key", "KEY", "", "", "private", "", "", true, "phrase"));
        assertEquals("KEY", key.type());
        assertFalse(key.hasPassword());
        assertEquals("", credentials.resolve(key.id(), 7L, false).password());
        var password = credentials.save(key.id(), new CredentialModels.Command("account", "PASSWORD", "deploy", "", "", "", "next", true, ""));
        assertEquals("PASSWORD", password.type());
        assertFalse(password.hasPrivateKey());
        assertFalse(password.hasPassphrase());
    }

    /** 两种类型均拒绝混合材料，并返回可翻译的业务错误。 */
    @ParameterizedTest
    @ValueSource(strings = {"KEY", "PASSWORD"})
    void rejectsMixedMaterials(String type) {
        var mixed = new CredentialModels.Command("mixed", type, "deploy", "", "private", "", "secret", true, "");
        assertEquals("server.credentialTypeConflict", assertThrows(BusinessException.class,
            () -> credentials.save(null, mixed)).getMessageKey());
        assertTrue(credentials.list().isEmpty());
    }

    /** 历史组合凭据继续供原服务器使用，引用期间不得改成单一类型。 */
    @Test
    void preservesLegacyCombinedCredentials() {
        var account = credentials.save(null, command("legacy", "", "password", ""));
        jdbc.update("UPDATE server_credential SET credential_type='RSA',private_key_encrypted=? WHERE id=?",
            crypto.encrypt("private"), account.id());
        var server = servers.create(server(account.id(), "KEY_PASSWORD"));
        assertEquals("RSA", credentials.list().get(0).type());
        assertEquals("private", servers.requireDataSyncTarget(server.id(), 7L).privateKey());
        assertEquals("password", servers.requireDataSyncTarget(server.id(), 7L).password());
        assertEquals(409, assertThrows(BusinessException.class,
            () -> credentials.save(account.id(), command("convert", "", "next", ""))).getStatus());
        assertEquals("password", credentials.resolve(account.id(), 7L, false).password());
    }

    /** 已引用的新类型禁止切换，历史单一密码可保留密文并显式更新类型。 */
    @Test
    void protectsReferencedTypeAndNormalizesLegacyAccount() {
        var account = credentials.save(null, command("account", "", "secret", ""));
        jdbc.update("UPDATE server_credential SET credential_type='RSA' WHERE id=?", account.id());
        assertEquals("PASSWORD", credentials.list().get(0).type());
        servers.create(server(account.id(), "PASSWORD"));
        credentials.save(account.id(), command("rename", "", "", ""));
        assertEquals("PASSWORD", jdbc.queryForObject("SELECT credential_type FROM server_credential WHERE id=?", String.class, account.id()));
        assertEquals(409, assertThrows(BusinessException.class,
            () -> credentials.save(account.id(), command("switch", "private", "", ""))).getStatus());
        assertEquals("secret", credentials.resolve(account.id(), 7L, false).password());
    }

    /** 缺失私钥、账号密码和掩码新建均返回业务错误，不落库。 */
    @ParameterizedTest
    @ValueSource(strings = {"KEY", "PASSWORD"})
    void rejectsMissingSecrets(String type) {
        for (String secret : new String[]{"", "******"}) {
            var command = new CredentialModels.Command("empty", type, "deploy", "", secret, "", "", true, "");
            assertThrows(BusinessException.class, () -> credentials.save(null, command));
            assertTrue(credentials.list().isEmpty());
        }
    }

    /** 按认证材料构造互斥类型，测试不隐藏任何混合输入。 */
    private CredentialModels.Command command(String label, String privateKey, String password, String passphrase) {
        return new CredentialModels.Command(label, privateKey.isEmpty() ? "PASSWORD" : "KEY", "deploy", "", privateKey, "", password, true, passphrase);
    }

    /** 构造仅引用凭据且账号由凭据提供的 SSH 服务器。 */
    private ServerModels.ServerCommand server(Long id, String authType) {
        return new ServerModels.ServerCommand("server", "SSH", "host", 22, "", authType, "", "", "", "", "", "", true, id);
    }

    /** 设置可重复的请求身份。 */
    private void authenticate(Long id, boolean admin) {
        AuthContext.set(new AuthUser(id, "user", Set.of(admin ? "ADMIN" : "USER"), Set.of(), AuthenticationType.TOKEN, null, null));
    }
}
