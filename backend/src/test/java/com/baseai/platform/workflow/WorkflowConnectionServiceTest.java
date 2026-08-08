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
            connection_type VARCHAR(24),config_encrypted CLOB,owner_user_id BIGINT,enabled BOOLEAN,voided BOOLEAN DEFAULT FALSE,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        jdbcTemplate.execute("CREATE TABLE workflow_version(id BIGINT AUTO_INCREMENT PRIMARY KEY,graph_json CLOB)");
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        service = new WorkflowConnectionService(jdbcTemplate, new ObjectMapper(), new ConfigCryptoService(properties));
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

    /** 设置当前会话用户。 */
    private void authenticate(Long id) {
        AuthContext.set(new AuthUser(id, "user" + id, Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));
    }
}
