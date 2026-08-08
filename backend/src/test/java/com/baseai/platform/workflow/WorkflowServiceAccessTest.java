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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WorkflowServiceAccessTest {
    private WorkflowService service;

    /** 创建两个所有者的画布和自定义模板，使用真实资源访问服务。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-service-access-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_definition(id BIGINT PRIMARY KEY,code VARCHAR(80),name VARCHAR(120),description VARCHAR(500),
            status VARCHAR(20),current_version_id BIGINT,published_version_id BIGINT,revision BIGINT,owner_user_id BIGINT,
            enabled BOOLEAN,voided BOOLEAN,created_at TIMESTAMP,updated_at TIMESTAMP)
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_version(id BIGINT PRIMARY KEY,workflow_id BIGINT,version_number INT,graph_json CLOB,
            input_schema_json CLOB,template_snapshot_json CLOB,created_at TIMESTAMP)
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_node_template(id BIGINT PRIMARY KEY,code VARCHAR(80),name VARCHAR(120),node_type VARCHAR(24),
            description VARCHAR(500),config_encrypted CLOB,system_template BOOLEAN,template_source VARCHAR(20),
            functional_category VARCHAR(40),enabled BOOLEAN,voided BOOLEAN,created_by BIGINT,created_at TIMESTAMP,updated_at TIMESTAMP)
            """);
        jdbcTemplate.update("INSERT INTO workflow_version VALUES (10,1,1,'{}','{}','{}',CURRENT_TIMESTAMP),(20,2,1,'{}','{}','{}',CURRENT_TIMESTAMP)");
        jdbcTemplate.update("""
            INSERT INTO workflow_definition VALUES
            (1,'OWN','Own','', 'DRAFT',10,NULL,1,7,true,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
            (2,'OTHER','Other','', 'DRAFT',20,NULL,1,8,true,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """);
        jdbcTemplate.update("""
            INSERT INTO workflow_node_template VALUES
            (1,'START','Start','START','','',true,'SYSTEM','FLOW_CONTROL',true,false,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
            (2,'OWN_NODE','Own','HTTP','','',false,'CUSTOM','INTEGRATION',true,false,7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
            (3,'OTHER_NODE','Other','HTTP','','',false,'CUSTOM','INTEGRATION',true,false,8,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """);
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        ObjectMapper mapper = new ObjectMapper();
        service = new WorkflowService(jdbcTemplate, mapper, new ConfigCryptoService(properties),
            mock(WorkflowGraphValidator.class), mock(WorkflowNodeConfigValidator.class), mock(WorkflowConnectionService.class),
            new WorkflowAccessService(jdbcTemplate));
        authenticate(7L, Set.of("USER"));
    }

    /** 清理线程认证上下文。 */
    @AfterEach
    void tearDown() { AuthContext.clear(); }

    /** 普通用户只能枚举自己的画布、系统模板和自己的自定义模板。 */
    @Test
    void filtersWorkflowAndTemplateListsByOwner() {
        assertEquals(Set.of("OWN"), service.workflows().stream().map(WorkflowModels.WorkflowView::code).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("START", "OWN_NODE"), service.templates().stream().map(WorkflowModels.NodeTemplateView::code)
            .collect(java.util.stream.Collectors.toSet()));
        assertThrows(BusinessException.class, () -> service.workflow(2L));
        assertThrows(BusinessException.class, () -> service.template(3L));
    }

    /** 管理员保留全局审计和维护视图。 */
    @Test
    void adminCanListAllResources() {
        authenticate(1L, Set.of("ADMIN"));
        assertEquals(2, service.workflows().size());
        assertEquals(3, service.templates().size());
    }

    /** 设置交互用户身份。 */
    private void authenticate(Long userId, Set<String> roles) {
        AuthContext.set(new AuthUser(userId, "user" + userId, roles, Set.of(), AuthenticationType.TOKEN, null, null));
    }
}
