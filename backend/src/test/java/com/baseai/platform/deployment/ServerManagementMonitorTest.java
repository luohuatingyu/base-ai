package com.baseai.platform.deployment;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.service.TaskTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖服务器监控的实时代理、所有权和异常响应边界。 */
class ServerManagementMonitorTest {
    private static final String SYSTEM_INFO = """
        {"family":"Linux","id":"alinux","name":"Alibaba Cloud Linux 3","version":"3",
         "kernel":"5.10.134","architecture":"aarch64","detectedAt":"2026-09-11T08:00:00Z"}
        """;

    /** 白名单错误保留具体诊断，未知远端文本继续被隐藏。 */
    @ParameterizedTest
    @ValueSource(strings = {"SSH_LOCAL_USER_MISSING", "SSH_AUTHENTICATION_FAILED", "SSH_CONNECTION_TIMEOUT",
        "SSH_CONNECTION_REFUSED", "SSH_HOST_UNRESOLVED", "SSH_NETWORK_UNREACHABLE", "SSH_PRIVATE_KEY_INVALID",
        "SSH_COMMAND_FAILED", "MONITOR_OS_UNSUPPORTED", "MONITOR_OUTPUT_INVALID"})
    void preservesSafeDiagnostics(String code) throws Exception {
        HttpServer agent = monitorAgent("{\"status\":\"FAILED\",\"error\":\"" + code + "\"}");
        try {
            ServerManagementService service = service(serverDatabase("diagnostic-" + code, true), address(agent));
            authenticate(7L);
            assertEquals(code, service.monitor(1L).error());
            assertEquals(code, service.test(1L).get("error"));
            assertEquals(code, service.servers().get(0).lastTestError());
        } finally { agent.stop(0); }
    }

    /** 连接探测后列表持久展示系统信息，重新编辑目标时清理旧系统身份。 */
    @Test
    void persistsSystemIdentityAndInvalidatesChangedTarget() throws Exception {
        HttpServer agent = monitorAgent("{\"status\":\"SUCCEEDED\",\"systemInfo\":" + SYSTEM_INFO + "}");
        try {
            JdbcTemplate database = serverDatabase("system-persist", true);
            ServerManagementService service = service(database, address(agent));
            authenticate(7L);
            assertNull(service.servers().get(0).systemInfo());
            java.util.Map<String, Object> connection = service.test(1L);
            assertNotNull(connection.get("systemInfo"));
            assertEquals("CONNECTION_SUCCEEDED", connection.get("output"));
            ServerModels.ServerView saved = service.servers().get(0);
            assertEquals("Alibaba Cloud Linux 3", saved.systemInfo().name());
            assertEquals("aarch64", saved.systemInfo().architecture());
            assertEquals("SUCCEEDED", saved.lastTestStatus());
            String encrypted = database.queryForObject("SELECT config_encrypted FROM managed_server WHERE id=1", String.class);
            assertTrue(!encrypted.contains("Alibaba") && !encrypted.contains("PRIVATE"));
            assertEquals("PRIVATE", new ObjectMapper().readTree(new ConfigCryptoService(properties()).decrypt(encrypted)).path("privateKey").asText());
            service.update(1L, serverCommand("host"));
            assertNotNull(service.servers().get(0).systemInfo());
            service.update(1L, serverCommand("other-host"));
            assertNull(service.servers().get(0).systemInfo());
        } finally { agent.stop(0); }
    }

    /** 资源不支持或采集失败时仍保存有效系统身份，同时不伪造资源指标。 */
    @Test
    void persistsIdentityWhenMonitoringUnsupported() throws Exception {
        HttpServer agent = monitorAgent("{\"status\":\"FAILED\",\"error\":\"MONITOR_OS_UNSUPPORTED\",\"systemInfo\":"
            + SYSTEM_INFO.replace("Linux", "Darwin") + "}");
        try {
            ServerManagementService service = service(serverDatabase("system-unsupported", true), address(agent));
            authenticate(7L);
            ServerModels.ServerMonitorView result = service.monitor(1L);
            assertEquals("MONITOR_OS_UNSUPPORTED", result.error());
            assertNull(result.host());
            assertEquals("Darwin", service.servers().get(0).systemInfo().family());
        } finally { agent.stop(0); }
    }

    /** 系统信息异常不得影响已成功的连接，且不得污染列表。 */
    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"family\":\"Linux\"}", "{\"detectedAt\":\"invalid\"}"})
    void ignoresMalformedIdentity(String identity) throws Exception {
        HttpServer agent = monitorAgent("{\"status\":\"SUCCEEDED\",\"systemInfo\":" + identity + "}");
        try {
            ServerManagementService service = service(serverDatabase("system-invalid-" + identity.hashCode(), true), address(agent));
            authenticate(7L);
            assertEquals("SUCCEEDED", service.test(1L).get("status"));
            assertNull(service.servers().get(0).systemInfo());
        } finally { agent.stop(0); }
    }

    /** 合法 SSH 成功响应中的超长、控制字符和伪造标识均不能保存。 */
    @ParameterizedTest
    @ValueSource(strings = {"oversized", "control", "invalid-id", "invalid-date", "null-field"})
    void rejectsUnsafeIdentityFields(String scenario) throws Exception {
        String identity = switch (scenario) {
            case "oversized" -> SYSTEM_INFO.replace("5.10.134", "a".repeat(257));
            case "control" -> SYSTEM_INFO.replace("5.10.134", "bad\\u001bvalue");
            case "invalid-id" -> SYSTEM_INFO.replace("alinux", "<script>");
            case "invalid-date" -> SYSTEM_INFO.replace("2026-09-11T08:00:00Z", "bad-date");
            default -> SYSTEM_INFO.replace("\"aarch64\"", "null");
        };
        HttpServer agent = monitorAgent("{\"status\":\"SUCCEEDED\",\"systemInfo\":" + identity + "}");
        try {
            ServerManagementService service = service(serverDatabase("unsafe-system-" + scenario, true), address(agent));
            authenticate(7L);
            assertEquals("SUCCEEDED", service.test(1L).get("status"));
            assertNull(service.servers().get(0).systemInfo());
        } finally { agent.stop(0); }
    }

    /** Agent HTTP 认证失败必须与内部通信故障区分，且不暴露响应体。 */
    @Test
    void distinguishesAgentAuthorizationAndTransportFailure() throws Exception {
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/monitor", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        agent.start();
        ServerManagementService service = service(serverDatabase("agent-transport", true), address(agent));
        authenticate(7L);
        try { assertEquals("server.agentUnauthorized", service.monitor(1L).error()); }
        finally { agent.stop(0); }
        assertEquals("server.agentUnavailable", service.monitor(1L).error());
    }

    /** 长时间探测返回后不能覆盖期间已经更新的连接配置。 */
    @Test
    void doesNotOverwriteConcurrentCredentialEdit() throws Exception {
        JdbcTemplate database = serverDatabase("system-concurrent", true);
        ConfigCryptoService crypto = new ConfigCryptoService(properties());
        String replacement = crypto.encrypt("{\"host\":\"new-host\",\"privateKey\":\"NEW_PRIVATE\"}");
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/test", exchange -> {
            exchange.getRequestBody().readAllBytes();
            database.update("UPDATE managed_server SET config_encrypted=? WHERE id=1", replacement);
            byte[] body = ("{\"status\":\"SUCCEEDED\",\"systemInfo\":" + SYSTEM_INFO + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        agent.start();
        try {
            authenticate(7L);
            service(database, address(agent)).test(1L);
            assertEquals(replacement, database.queryForObject("SELECT config_encrypted FROM managed_server WHERE id=1", String.class));
            assertNull(database.queryForObject("SELECT last_test_status FROM managed_server WHERE id=1", String.class));
        } finally { agent.stop(0); }
    }

    /** 返回本地测试 Agent 地址。 */
    private String address(HttpServer agent) { return "http://127.0.0.1:" + agent.getAddress().getPort(); }

    /** 构造保留现有凭据的合法服务器编辑命令。 */
    private ServerModels.ServerCommand serverCommand(String host) {
        return new ServerModels.ServerCommand("renamed", "SSH", host, 22, "deploy", "KEY", "", "", "", "", "", "", true);
    }

    /** 每个测试结束后清除线程级认证信息。 */
    @AfterEach
    void tearDown() { AuthContext.clear(); }

    /** 所有者实时查询时应向 Agent 发送凭据并返回结构化快照。 */
    @Test
    void returnsLiveMonitorSnapshotForOwner() throws Exception {
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/monitor", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("Bearer internal-token-with-24-characters", authorization);
            assertTrue(body.contains("\"privateKey\":\"PRIVATE\""));
            byte[] response = """
                {"status":"SUCCEEDED","collectedAt":"2026-09-10T08:00:00Z","host":{"cpuCores":4,
                "cpuUsagePercent":25.5,"load1":0.1,"load5":0.2,"load15":0.3,"memoryTotalBytes":1000,
                "memoryUsedBytes":400,"memoryUsagePercent":40.0,"diskPath":"/","diskTotalBytes":2000,
                "diskUsedBytes":500,"diskUsagePercent":25.0,"uptimeSeconds":3600},"containers":[
                {"id":"0123456789ab","name":"api","image":"base-ai:latest","state":"RUNNING",
                "health":"HEALTHY","status":"Up 2 minutes (healthy)"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        agent.start();
        try {
            JdbcTemplate database = serverDatabase("monitor-success", true);
            ServerManagementService service = service(database, "http://127.0.0.1:" + agent.getAddress().getPort());
            authenticate(7L);

            ServerModels.ServerMonitorView result = service.monitor(1L);

            assertEquals("SUCCEEDED", result.status());
            assertEquals(4, result.host().cpuCores());
            assertTrue(result.containers().isEmpty());
            assertNotNull(result.collectedAt());
        } finally {
            agent.stop(0);
        }
    }

    /** 兼容旧 Agent 的 Docker 错误，主机指标有效时仍返回成功。 */
    @Test
    void keepsHostMetricsWhenContainerQueryFails() throws Exception {
        HttpServer agent = monitorAgent("""
            {"status":"PARTIAL","collectedAt":"2026-09-10T08:00:00Z","host":{"cpuCores":2,
            "cpuUsagePercent":10.0,"load1":0.0,"load5":0.0,"load15":0.0,"memoryTotalBytes":100,
            "memoryUsedBytes":20,"memoryUsagePercent":20.0,"diskPath":"/","diskTotalBytes":100,
            "diskUsedBytes":10,"diskUsagePercent":10.0,"uptimeSeconds":60},"containers":[],
            "containerError":"Docker unavailable"}
            """);
        try {
            ServerManagementService service = service(serverDatabase("monitor-partial", true),
                "http://127.0.0.1:" + agent.getAddress().getPort());
            authenticate(7L);

            ServerModels.ServerMonitorView result = service.monitor(1L);

            assertEquals("SUCCEEDED", result.status());
            org.junit.jupiter.api.Assertions.assertNull(result.containerError());
            assertEquals(2, result.host().cpuCores());
        } finally {
            agent.stop(0);
        }
    }

    /** 越权、停用和不存在的服务器都不得触发 Agent 查询。 */
    @Test
    void rejectsForbiddenDisabledAndMissingServers() {
        ServerManagementService enabled = service(serverDatabase("monitor-owner", true), "http://agent");
        authenticate(8L);
        assertEquals("server.accessForbidden", assertThrows(BusinessException.class,
            () -> enabled.monitor(1L)).getMessageKey());

        ServerManagementService disabled = service(serverDatabase("monitor-disabled", false), "http://agent");
        authenticate(7L);
        assertEquals("server.disabled", assertThrows(BusinessException.class,
            () -> disabled.monitor(1L)).getMessageKey());
        assertEquals("server.notFound", assertThrows(BusinessException.class,
            () -> disabled.monitor(99L)).getMessageKey());
    }

    /** Agent 返回越界数值时必须降级为无敏感细节的失败结果。 */
    @Test
    void rejectsInvalidAgentMetrics() throws Exception {
        HttpServer agent = monitorAgent("""
            {"status":"SUCCEEDED","collectedAt":"2026-09-10T08:00:00Z","host":{"cpuCores":2,
            "cpuUsagePercent":101.0,"load1":0.0,"load5":0.0,"load15":0.0,"memoryTotalBytes":100,
            "memoryUsedBytes":20,"memoryUsagePercent":20.0,"diskPath":"/","diskTotalBytes":100,
            "diskUsedBytes":10,"diskUsagePercent":10.0,"uptimeSeconds":60},"containers":[]}
            """);
        try {
            ServerManagementService service = service(serverDatabase("monitor-invalid", true),
                "http://127.0.0.1:" + agent.getAddress().getPort());
            authenticate(7L);

            ServerModels.ServerMonitorView result = service.monitor(1L);

            assertEquals("FAILED", result.status());
            assertEquals("server.agentInvalidResponse", result.error());
        } finally {
            agent.stop(0);
        }
    }

    /** Agent 失败详情可能包含主机信息，接口必须统一返回安全错误键。 */
    @Test
    void hidesAgentFailureDetails() throws Exception {
        HttpServer agent = monitorAgent("""
            {"status":"FAILED","collectedAt":"2026-09-10T08:00:00Z","containers":[],
            "error":"ssh deploy@example.internal failed with private details"}
            """);
        try {
            ServerManagementService service = service(serverDatabase("monitor-failed", true),
                "http://127.0.0.1:" + agent.getAddress().getPort());
            authenticate(7L);

            ServerModels.ServerMonitorView result = service.monitor(1L);

            assertEquals("FAILED", result.status());
            assertEquals("server.monitorFailed", result.error());
        } finally {
            agent.stop(0);
        }
    }

    /** 创建返回固定 JSON 的监控 Agent 替身。 */
    private HttpServer monitorAgent(String json) throws Exception {
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        };
        agent.createContext("/monitor", handler);
        agent.createContext("/test", handler);
        agent.start();
        return agent;
    }

    /** 创建仅包含监控查询所需字段的服务器表。 */
    private JdbcTemplate serverDatabase(String name, boolean enabled) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        database.execute("""
            CREATE TABLE managed_server(
              id BIGINT PRIMARY KEY,name VARCHAR(120),mode VARCHAR(12),owner_user_id BIGINT,
              enabled BOOLEAN,voided BOOLEAN,config_encrypted CLOB,host VARCHAR(255),port INT,username VARCHAR(64),
              last_test_status VARCHAR(32),last_test_error VARCHAR(500),last_test_at TIMESTAMP,
              created_at TIMESTAMP,updated_at TIMESTAMP)
            """);
        ConfigCryptoService crypto = new ConfigCryptoService(properties());
        String config = crypto.encrypt("""
            {"mode":"SSH","host":"host","port":22,"username":"deploy","authType":"KEY","privateKey":"PRIVATE",
             "password":"","passphrase":"","hostKey":"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
             "workingDir":"/opt/base-ai","composeFile":"docker-compose.yml"}
            """);
        database.update("INSERT INTO managed_server(id,name,mode,owner_user_id,enabled,voided,config_encrypted,host,port,username) VALUES (1,'server','SSH',7,?,false,?,'host',22,'deploy')", enabled, config);
        return database;
    }

    /** 创建使用可控 Agent 地址的服务器服务。 */
    private ServerManagementService service(JdbcTemplate database, String agentUrl) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new ServerManagementService(database, objectMapper, new ConfigCryptoService(properties()),
            Mockito.mock(TaskTraceService.class), Mockito.mock(ThreadPoolTaskExecutor.class), agentUrl,
            "internal-token-with-24-characters");
    }

    /** 设置普通资源所有者身份。 */
    private void authenticate(Long id) {
        AuthContext.set(new AuthUser(id, "user-" + id, Set.of("USER"), Set.of("operations:server:test"),
            AuthenticationType.TOKEN, null, null));
    }

    /** 创建加密服务所需的固定测试密钥。 */
    private static PlatformProperties properties() {
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        return properties;
    }
}
