package com.baseai.platform.deployment;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.service.TaskTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 覆盖服务器配置的模式、SSH 凭据和路径安全校验。 */
class ServerManagementValidationTest {
    private final ServerManagementService service = new ServerManagementService(Mockito.mock(JdbcTemplate.class),
        new ObjectMapper(), new ConfigCryptoService(properties()), Mockito.mock(TaskTraceService.class),
        Mockito.mock(ThreadPoolTaskExecutor.class), "", "");

    /** 每个用例结束后清除线程身份，避免影响其他测试。 */
    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        AuthContext.clear();
    }

    /** 非法模式必须被拒绝。 */
    @Test
    void rejectsInvalidMode() {
        ServerModels.ServerCommand command = new ServerModels.ServerCommand("server", "SHELL", "", null, "", "KEY", "", "", "", "", "/opt/base-ai", "docker-compose.yml", true);
        assertEquals("server.modeInvalid", assertThrows(BusinessException.class, () -> service.create(command)).getMessageKey());
    }

    /** SSH 配置必须有主机指纹和对应认证凭据。 */
    @Test
    void rejectsIncompleteSsh() {
        ServerModels.ServerCommand command = new ServerModels.ServerCommand("server", "SSH", "host", 22, "deploy", "KEY", "", "", "", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "/opt/base-ai", "docker-compose.yml", true);
        assertEquals("server.privateKeyRequired", assertThrows(BusinessException.class, () -> service.create(command)).getMessageKey());
    }

    /** 新增服务器只需连接配置，Compose 目录和文件均可留空。 */
    @Test
    void createsServerWithoutComposeConfiguration() {
        JdbcTemplate database = Mockito.spy(serverDatabase("create-without-compose"));
        Mockito.doReturn(1L).when(database).queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        ServerManagementService creating = serverService(database, Mockito.mock(TaskTraceService.class), "");
        AuthContext.set(new AuthUser(7L, "owner", Set.of("USER"), Set.of("operations:server:create"),
            AuthenticationType.TOKEN, null, null));
        ServerModels.ServerCommand command = new ServerModels.ServerCommand("server", "SSH", "host", 22,
            "deploy", "KEY", "PRIVATE", "", "", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            null, null, true);

        ServerModels.ServerView created = creating.create(command);

        assertEquals("", created.workingDir());
        assertEquals("", created.composeFile());
    }

    /** Compose 兼容字段必须同时提供，避免半配置绕过路径安全校验。 */
    @Test
    void rejectsPartialComposeConfiguration() {
        ServerModels.ServerCommand command = new ServerModels.ServerCommand("server", "SSH", "host", 22,
            "deploy", "KEY", "PRIVATE", "", "", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "", "compose.yml", true);

        assertEquals("server.invalid",
            assertThrows(BusinessException.class, () -> service.create(command)).getMessageKey());
    }

    /** 密码认证必须提供密码，且 Host Key 不允许使用部分指纹。 */
    @Test
    void rejectsMissingPasswordAndPartialFingerprint() {
        ServerModels.ServerCommand password = new ServerModels.ServerCommand("server", "SSH", "host", 22,
            "deploy", "PASSWORD", "", "", "", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "/opt/base-ai", "docker-compose.yml", true);
        assertEquals("server.passwordRequired",
            assertThrows(BusinessException.class, () -> service.create(password)).getMessageKey());
        ServerModels.ServerCommand fingerprint = new ServerModels.ServerCommand("server", "SSH", "host", 22,
            "deploy", "KEY", "PRIVATE", "", "", "SHA256:AAAA", "/opt/base-ai", "docker-compose.yml", true);
        assertEquals("server.hostKeyRequired",
            assertThrows(BusinessException.class, () -> service.create(fingerprint)).getMessageKey());
    }

    /** Compose 目录不允许携带 Shell 元字符。 */
    @Test
    void rejectsUnsafePath() {
        ServerModels.ServerCommand command = new ServerModels.ServerCommand("server", "LOCAL", "", null, "", "KEY", "", "", "", "", "/opt/base-ai;id", "docker-compose.yml", true);
        assertEquals("server.invalid", assertThrows(BusinessException.class, () -> service.create(command)).getMessageKey());
    }

    /** SSH 用户名选项注入和包含父目录的路径必须被拒绝。 */
    @Test
    void rejectsUnsafeUsernameAndParentPath() {
        ServerModels.ServerCommand username = new ServerModels.ServerCommand("server", "SSH", "host", 22,
            "-oProxyCommand=id", "KEY", "PRIVATE", "", "",
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "/opt/base-ai", "docker-compose.yml", true);
        assertEquals("server.sshRequired",
            assertThrows(BusinessException.class, () -> service.create(username)).getMessageKey());
        ServerModels.ServerCommand parentPath = new ServerModels.ServerCommand("server", "SSH", "host", 22,
            "deploy", "KEY", "PRIVATE", "", "", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "/opt/base-ai/../other", "docker-compose.yml", true);
        assertEquals("server.invalid",
            assertThrows(BusinessException.class, () -> service.create(parentPath)).getMessageKey());
    }

    /** 发布版本必须能直接作为 Docker 镜像标签使用。 */
    @Test
    void rejectsInvalidDockerTagRevision() {
        ServerModels.DeploymentCommand command = new ServerModels.DeploymentCommand("DEPLOY", "registry:tag");
        assertEquals("server.revisionInvalid",
            assertThrows(BusinessException.class, () -> service.validateDeployment(command)).getMessageKey());
    }

    /** 切换认证方式时不得错误复用另一种认证的旧凭据。 */
    @Test
    void rejectsCredentialTypeChangeWithoutNewCredential() throws Exception {
        JsonNode old = new ObjectMapper().readTree("""
            {"mode":"SSH","host":"host","port":22,"username":"deploy","authType":"KEY",
             "privateKey":"PRIVATE","password":"","passphrase":"","hostKey":"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
             "workingDir":"/opt/base-ai","composeFile":"docker-compose.yml"}
            """);
        ServerModels.ServerCommand command = new ServerModels.ServerCommand("server", "SSH", "host", 22,
            "deploy", "PASSWORD", "", "", "", "******", "/opt/base-ai", "docker-compose.yml", true);
        JsonNode merged = service.merge(old, command);
        assertEquals("server.passwordRequired",
            assertThrows(BusinessException.class, () -> service.validateMergedCredential(command, merged)).getMessageKey());
    }

    /** 切换为本地模式时必须清除历史 SSH 凭据。 */
    @Test
    void clearsSshCredentialWhenSwitchingToLocalMode() throws Exception {
        JsonNode old = new ObjectMapper().readTree("""
            {"authType":"KEY","privateKey":"PRIVATE","password":"secret","passphrase":"phrase",
             "hostKey":"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
            """);
        ServerModels.ServerCommand command = new ServerModels.ServerCommand("local", "LOCAL", "", null,
            "", "", "", "", "", "", "/workspace", "docker-compose.yml", true);
        JsonNode merged = service.merge(old, command);
        assertEquals("", merged.path("privateKey").asText());
        assertEquals("", merged.path("password").asText());
        assertEquals("", merged.path("hostKey").asText());
    }

    /** Agent 仅可返回 Backend 已持久化的精确任务编号。 */
    @Test
    void validatesAgentJobIdentifier() {
        String jobId = "0123456789abcdef0123456789abcdef";
        assertEquals(jobId, service.requireAgentJobId(Map.of("jobId", jobId), jobId));
        assertThrows(BusinessException.class,
            () -> service.requireAgentJobId(Map.of("jobId", "fedcba9876543210fedcba9876543210"), jobId));
        assertThrows(BusinessException.class,
            () -> service.requireAgentJobId(Map.of("jobId", "../other"), jobId));
    }

    /** Backend 重启后应通过 Agent 任务编号恢复成功结果并释放并发槽。 */
    @Test
    void reconcilesCompletedAgentJob() throws Exception {
        String jobId = "0123456789abcdef0123456789abcdef";
        HttpServer agentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agentServer.createContext("/jobs/" + jobId, exchange -> {
            byte[] response = "{\"status\":\"SUCCEEDED\",\"output\":\"updated\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        agentServer.start();
        try {
            JdbcTemplate database = deploymentDatabase("reconcile-success");
            database.update("INSERT INTO deployment_run VALUES (1,'trace-success',?,'RUNNING',1,NULL,?,NULL)",
                "agent-job:" + jobId, LocalDateTime.now());
            TaskTraceService traceService = Mockito.mock(TaskTraceService.class);
            ServerManagementService recovering = serverService(database, traceService,
                "http://127.0.0.1:" + agentServer.getAddress().getPort());

            recovering.reconcileAgentJobs();

            assertEquals("SUCCEEDED", database.queryForObject("SELECT status FROM deployment_run WHERE id=1", String.class));
            assertEquals("updated", database.queryForObject("SELECT output_summary FROM deployment_run WHERE id=1", String.class));
            assertNull(database.queryForObject("SELECT active_slot FROM deployment_run WHERE id=1", Integer.class));
            Mockito.verify(traceService).markSuccess("trace-success");
        } finally {
            agentServer.stop(0);
        }
    }

    /** 超过 Agent 执行上限的遗留任务必须失败并释放并发槽。 */
    @Test
    void failsUnrecoverableAgentJobAfterTimeout() {
        JdbcTemplate database = deploymentDatabase("reconcile-timeout");
        database.update("INSERT INTO deployment_run VALUES (1,'trace-timeout',?,'RUNNING',1,NULL,?,NULL)",
            "agent-job:0123456789abcdef0123456789abcdef", LocalDateTime.now().minusMinutes(17));
        TaskTraceService traceService = Mockito.mock(TaskTraceService.class);
        ServerManagementService recovering = serverService(database, traceService, "");

        recovering.reconcileAgentJobs();

        assertEquals("FAILED", database.queryForObject("SELECT status FROM deployment_run WHERE id=1", String.class));
        assertEquals("server.deployTimeout", database.queryForObject("SELECT error_message FROM deployment_run WHERE id=1", String.class));
        assertNull(database.queryForObject("SELECT active_slot FROM deployment_run WHERE id=1", Integer.class));
        Mockito.verify(traceService).markFailed("trace-timeout", "server.deployTimeout");
    }

    /** 创建部署恢复测试使用的最小 H2 表。 */
    private JdbcTemplate deploymentDatabase(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        database.execute("""
            CREATE TABLE deployment_run(
              id BIGINT PRIMARY KEY,trace_id VARCHAR(36),output_summary VARCHAR(2000),status VARCHAR(24),
              active_slot INT,error_message VARCHAR(1000),started_at TIMESTAMP,finished_at TIMESTAMP)
            """);
        return database;
    }

    /** 创建服务器新增测试使用的最小 H2 表。 */
    private JdbcTemplate serverDatabase(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        database.execute("""
            CREATE TABLE managed_server(
              id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(120),mode VARCHAR(12),host VARCHAR(255),
              port INT,username VARCHAR(120),config_encrypted CLOB,owner_user_id BIGINT,enabled BOOLEAN,
              voided BOOLEAN DEFAULT FALSE,last_test_status VARCHAR(24),last_test_error VARCHAR(500),
              last_test_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        return database;
    }

    /** 创建带可控 Agent 地址的服务器管理服务。 */
    private ServerManagementService serverService(JdbcTemplate database, TaskTraceService traceService, String agentUrl) {
        return new ServerManagementService(database, new ObjectMapper(), new ConfigCryptoService(properties()), traceService,
            Mockito.mock(ThreadPoolTaskExecutor.class), agentUrl, "internal-token-with-24-characters");
    }

    /** 创建加密服务所需的固定测试密钥。 */
    private static PlatformProperties properties() {
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        return properties;
    }
}
