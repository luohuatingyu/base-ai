package com.baseai.platform.workflow;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowConnectionServiceTest {
    private JdbcTemplate jdbcTemplate;
    private WorkflowConnectionService service;

    /** 创建独立 H2 表和固定测试密钥。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-connection-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_connection(id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(80) UNIQUE,name VARCHAR(120),
            connection_type VARCHAR(24),plugin_component_id BIGINT,config_encrypted CLOB,owner_user_id BIGINT,enabled BOOLEAN,voided BOOLEAN DEFAULT FALSE,
            security_revision BIGINT DEFAULT 1,vector_status VARCHAR(16) DEFAULT 'UNKNOWN',vector_engine VARCHAR(32),
            vector_version VARCHAR(64),vector_checked_at TIMESTAMP,vector_error VARCHAR(500) DEFAULT '',
            last_test_at TIMESTAMP,last_test_ok BOOLEAN,last_test_latency_ms INT,last_test_info CLOB,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        jdbcTemplate.execute("CREATE TABLE workflow_version(id BIGINT AUTO_INCREMENT PRIMARY KEY,graph_json CLOB)");
        jdbcTemplate.execute("CREATE TABLE workflow_version_connection(workflow_version_id BIGINT,connection_id BIGINT,security_revision BIGINT)");
        jdbcTemplate.execute("CREATE TABLE data_sync_plan(id BIGINT AUTO_INCREMENT PRIMARY KEY,source_connection_id BIGINT,target_connection_id BIGINT,voided BOOLEAN DEFAULT FALSE)");
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        service = new WorkflowConnectionService(jdbcTemplate, new ObjectMapper(), new ConfigCryptoService(properties),
            org.mockito.Mockito.mock(WorkflowNetworkPolicy.class));
        authenticate(7L);
    }

    /** 清理线程登录态。 */
    @AfterEach
    void tearDown() { AuthContext.clear(); }

    /** 密钥必须加密落库、脱敏返回并能通过占位符保留。 */
    @Test
    void encryptsMasksAndPreservesSecrets() throws Exception {
        WorkflowModels.ConnectionView created = service.create(new WorkflowModels.ConnectionCommand("orders", "Orders", "MYSQL",
            new ObjectMapper().readTree("{\"url\":\"jdbc:mysql://db/orders\",\"username\":\"app\",\"password\":\"secret\"}"), true));
        assertEquals("******", created.config().path("password").asText());
        String ciphertext = jdbcTemplate.queryForObject("SELECT config_encrypted FROM workflow_connection WHERE id=?", String.class, created.id());
        assertFalse(ciphertext.contains("secret"));
        service.update(created.id(), new WorkflowModels.ConnectionCommand("ORDERS", "Orders", "MYSQL",
            new ObjectMapper().readTree("{\"url\":\"jdbc:mysql://db/orders\",\"username\":\"app\",\"password\":\"******\"}"), true));
        assertEquals("secret", service.resolved(created.id(), Set.of("MYSQL")).config().path("password").asText());
        assertEquals(1L, service.resolved(created.id(), Set.of("MYSQL")).securityRevision());

        service.update(created.id(), new WorkflowModels.ConnectionCommand("ORDERS", "Orders", "MYSQL",
            new ObjectMapper().readTree("{\"url\":\"jdbc:mysql://db-v2/orders\",\"username\":\"app\",\"password\":\"******\"}"), true));
        assertEquals(2L, service.resolved(created.id(), Set.of("MYSQL")).securityRevision());
    }

    /** Tavily API Key 必须使用连接加密保存并在所有管理视图中脱敏。 */
    @Test
    void encryptsAndMasksTavilyApiKey() throws Exception {
        WorkflowModels.ConnectionView created = service.create(new WorkflowModels.ConnectionCommand(
            "tavily", "Tavily", "TAVILY", new ObjectMapper().readTree("{\"apiKey\":\"tvly-secret\"}"), true));

        assertEquals("******", created.config().path("apiKey").asText());
        String ciphertext = jdbcTemplate.queryForObject(
            "SELECT config_encrypted FROM workflow_connection WHERE id=?", String.class, created.id());
        assertFalse(ciphertext.contains("tvly-secret"));
        assertEquals("tvly-secret", service.resolved(created.id(), Set.of("TAVILY")).config().path("apiKey").asText());
        assertEquals("workflow.connectionInvalid", assertThrows(BusinessException.class, () -> service.create(
            new WorkflowModels.ConnectionCommand("missing", "Missing", "TAVILY",
                new ObjectMapper().createObjectNode(), true))).getMessageKey());
    }

    /** 插件连接必须把组件身份同步到专用外键列，改绑时不能只改密文 JSON。 */
    @Test
    void persistsPluginComponentIdentity() throws Exception {
        WorkflowModels.ConnectionView created = service.create(new WorkflowModels.ConnectionCommand(
            "plugin", "Plugin", "PLUGIN", new ObjectMapper().readTree(
                "{\"pluginComponentId\":21,\"credentials\":{\"apiKey\":\"secret\"}}"), true));
        assertEquals(21L, jdbcTemplate.queryForObject(
            "SELECT plugin_component_id FROM workflow_connection WHERE id=?", Long.class, created.id()));

        service.update(created.id(), new WorkflowModels.ConnectionCommand("PLUGIN", "Plugin", "PLUGIN",
            new ObjectMapper().readTree("{\"pluginComponentId\":22,\"credentials\":{\"apiKey\":\"******\"}}"), true));
        assertEquals(22L, jdbcTemplate.queryForObject(
            "SELECT plugin_component_id FROM workflow_connection WHERE id=?", Long.class, created.id()));
        assertEquals("secret", service.resolved(created.id(), Set.of("PLUGIN")).config().path("credentials").path("apiKey").asText());
    }

    /** 其他用户不能维护不属于自己的连接。 */
    @Test
    void rejectsCrossOwnerUpdate() throws Exception {
        WorkflowModels.ConnectionView created = service.create(new WorkflowModels.ConnectionCommand("cache", "Cache", "REDIS",
            new ObjectMapper().readTree("{\"uri\":\"redis://cache:6379\"}"), true));
        authenticate(8L);
        assertThrows(BusinessException.class, () -> service.update(created.id(), new WorkflowModels.ConnectionCommand(
            "CACHE", "Cache", "REDIS", new ObjectMapper().readTree("{\"uri\":\"redis://cache:6379\"}"), true)));
    }

    /** 节点选择器只列出当前用户拥有的启用连接，且无需解密敏感配置。 */
    @Test
    void listsOnlyCurrentOwnerEnabledConnectionOptionsWithoutDecryptingConfig() {
        jdbcTemplate.update("""
            INSERT INTO workflow_connection(code,name,connection_type,config_encrypted,owner_user_id,enabled,voided)
            VALUES ('MYSQL_MAIN','Main','MYSQL','not-a-ciphertext',7,true,false),
                   ('CACHE_DISABLED','Cache','REDIS','not-a-ciphertext',7,false,false),
                   ('OTHER','Other','S3','not-a-ciphertext',8,true,false),
                   ('VOIDED','Voided','WEBHOOK','not-a-ciphertext',7,true,true)
            """);

        List<WorkflowConnectionService.ConnectionOption> options = service.connectionOptions();

        assertEquals(List.of(new WorkflowConnectionService.ConnectionOption(1L, "MYSQL_MAIN", "Main", "MYSQL")), options);
    }

    /** 精确版本连接快照中的引用必须阻止删除，不能依赖 JSON 字符串搜索。 */
    @Test
    void rejectsDeletingConnectionReferencedByVersionSnapshot() throws Exception {
        WorkflowModels.ConnectionView created = service.create(new WorkflowModels.ConnectionCommand("cache", "Cache", "REDIS",
            new ObjectMapper().readTree("{\"uri\":\"redis://cache:6379\"}"), true));
        jdbcTemplate.update("INSERT INTO workflow_version_connection VALUES (10,?,1)", created.id());
        assertEquals("workflow.connectionInUse", assertThrows(BusinessException.class,
            () -> service.delete(created.id())).getMessageKey());
    }

    /** OSS 连接可以正常创建、脱敏保存凭据并按对象存储类型解析。 */
    @Test
    void createsAndMasksOssConnection() throws Exception {
        WorkflowModels.ConnectionView created = service.create(new WorkflowModels.ConnectionCommand(
            "oss", "OSS", "OSS", new ObjectMapper().readTree(
                "{\"endpoint\":\"https://oss-cn-hangzhou.aliyuncs.com\",\"bucket\":\"workflow-files\",\"accessKey\":\"ak\",\"secretKey\":\"sk\"}"), true));

        assertEquals("OSS", created.connectionType());
        assertEquals("******", created.config().path("accessKey").asText());
        assertEquals("******", created.config().path("secretKey").asText());
        assertEquals("sk", service.resolved(created.id(), Set.of("OSS")).config().path("secretKey").asText());
    }

    /** 向量能力结果可展示，任何安全相关配置变更都会重置为未验证。 */
    @Test
    void recordsAndResetsVectorCapability() throws Exception {
        WorkflowModels.ConnectionView created=service.create(new WorkflowModels.ConnectionCommand("vectors","Vectors","QDRANT",
            new ObjectMapper().readTree("{\"url\":\"https://vectors.example.com\",\"apiKey\":\"secret\"}"),true));
        service.recordVectorCapability(created.id(),"SUPPORTED","QDRANT","1.13.0","");
        assertEquals("SUPPORTED",service.view(created.id()).vectorStatus());
        service.update(created.id(),new WorkflowModels.ConnectionCommand("VECTORS","Vectors","QDRANT",
            new ObjectMapper().readTree("{\"url\":\"https://vectors-v2.example.com\",\"apiKey\":\"******\"}"),true));
        assertEquals("UNKNOWN",service.view(created.id()).vectorStatus());
    }

    /** 最近检测结果必须留存并在列表视图中返回，供数据源页面展示健康状态。 */
    @Test
    void recordsAndExposesLastTestResult() throws Exception {
        WorkflowModels.ConnectionView created = service.create(new WorkflowModels.ConnectionCommand("main", "Main", "MYSQL",
            new ObjectMapper().readTree("{\"url\":\"jdbc:mysql://db/orders\",\"username\":\"app\",\"password\":\"secret\"}"), true));
        assertNull(service.view(created.id()).lastTestOk());

        service.recordTestResult(created.id(), true, 35, "{\"version\":\"8.0.36\",\"activeConnections\":12}");
        WorkflowModels.ConnectionView after = service.view(created.id());
        assertEquals(Boolean.TRUE, after.lastTestOk());
        assertEquals(35, after.lastTestLatencyMs());
        assertEquals("8.0.36", after.lastTestInfo().path("version").asText());
        assertEquals(12, after.lastTestInfo().path("activeConnections").asInt());

        service.recordTestResult(created.id(), false, 900, "{}");
        WorkflowModels.ConnectionView failed = service.connections().stream()
            .filter(item -> item.id().equals(created.id())).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, failed.lastTestOk());
        assertEquals(900, failed.lastTestLatencyMs());
    }

    /** 设置当前会话用户。 */
    private void authenticate(Long id) {
        AuthContext.set(new AuthUser(id, "user" + id, Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));
    }
}
