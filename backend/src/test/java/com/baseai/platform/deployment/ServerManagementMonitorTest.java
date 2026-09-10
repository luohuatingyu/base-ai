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
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖服务器监控的实时代理、所有权和异常响应边界。 */
class ServerManagementMonitorTest {
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
            assertEquals(1, result.containers().size());
            assertEquals("HEALTHY", result.containers().get(0).health());
            assertNotNull(result.collectedAt());
        } finally {
            agent.stop(0);
        }
    }

    /** Docker 不可用的部分成功响应仍应保留基础资源并返回受限错误。 */
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

            assertEquals("PARTIAL", result.status());
            assertEquals("Docker unavailable", result.containerError());
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
        agent.createContext("/monitor", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
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
              enabled BOOLEAN,voided BOOLEAN,config_encrypted CLOB)
            """);
        ConfigCryptoService crypto = new ConfigCryptoService(properties());
        String config = crypto.encrypt("""
            {"host":"host","port":22,"username":"deploy","authType":"KEY","privateKey":"PRIVATE",
             "password":"","passphrase":"","hostKey":"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
             "workingDir":"/opt/base-ai","composeFile":"docker-compose.yml"}
            """);
        database.update("INSERT INTO managed_server VALUES (1,'server','SSH',7,?,false,?)", enabled, config);
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
