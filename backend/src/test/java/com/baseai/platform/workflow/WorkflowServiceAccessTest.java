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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            CREATE TABLE workflow_node_template(id BIGINT AUTO_INCREMENT PRIMARY KEY,code VARCHAR(80),name VARCHAR(120),node_type VARCHAR(24),
            description VARCHAR(500),config_encrypted CLOB,system_template BOOLEAN,template_source VARCHAR(20),
            functional_category VARCHAR(40),external_key VARCHAR(255),external_version VARCHAR(64),external_publisher VARCHAR(120),
            external_fingerprint CHAR(64),imported_at TIMESTAMP,enabled BOOLEAN DEFAULT TRUE,voided BOOLEAN DEFAULT FALSE,
            created_by BIGINT,created_at TIMESTAMP,updated_at TIMESTAMP,
            UNIQUE(template_source,external_key))
            """);
        jdbcTemplate.update("INSERT INTO workflow_version VALUES (10,1,1,'{}','{}','{}',CURRENT_TIMESTAMP),(20,2,1,'{}','{}','{}',CURRENT_TIMESTAMP)");
        jdbcTemplate.update("""
            INSERT INTO workflow_definition VALUES
            (1,'OWN','Own','', 'DRAFT',10,NULL,1,7,true,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
            (2,'OTHER','Other','', 'DRAFT',20,NULL,1,8,true,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """);
        jdbcTemplate.update("""
            INSERT INTO workflow_node_template VALUES
            (1,'START','Start','START','','',true,'SYSTEM','FLOW_CONTROL',NULL,NULL,NULL,NULL,NULL,true,false,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
            (2,'OWN_NODE','Own','HTTP','','',false,'CUSTOM','INTEGRATION',NULL,NULL,NULL,NULL,NULL,true,false,7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
            (3,'OTHER_NODE','Other','HTTP','','',false,'CUSTOM','INTEGRATION',NULL,NULL,NULL,NULL,NULL,true,false,8,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
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

    /** 市场模板导入必须幂等保存外部身份并默认启用。 */
    @Test
    void importsMarketplaceTemplateIdempotently() {
        authenticate(1L, Set.of("ADMIN"));
        WorkflowModels.MarketplaceTemplateDraft draft = new WorkflowModels.MarketplaceTemplateDraft(
            "N8N", "n8n-nodes-base.redis", "1", "n8n", "a".repeat(64), "N8N_REDIS", "Redis", "",
            "REDIS_COMMAND", "DATA_STORAGE", new ObjectMapper().createObjectNode());

        WorkflowModels.MarketplaceTemplatePersistence first = service.importMarketplaceTemplate(draft);

        assertEquals("CREATED", first.status());
        WorkflowModels.NodeTemplateView imported = service.templates().stream()
            .filter(template -> "n8n-nodes-base.redis".equals(template.externalKey())).findFirst().orElseThrow();
        assertEquals(true, imported.importedTemplate());
        assertEquals(true, imported.enabled());
        assertEquals("n8n-nodes-base.redis", imported.externalKey());

        service.updateTemplate(first.templateId(), new WorkflowModels.NodeTemplateCommand(
            "IGNORED", "Redis", "REDIS_COMMAND", "", new ObjectMapper().createObjectNode(), false, "N8N", "DATA_STORAGE"));
        assertFalse(service.template(first.templateId()).enabled());

        WorkflowModels.MarketplaceTemplatePersistence second = service.importMarketplaceTemplate(draft);
        assertEquals("ALREADY_IMPORTED", second.status());
        assertTrue(service.template(first.templateId()).enabled());
    }

    /** 重新导入已删除的市场模板必须恢复记录并立即启用。 */
    @Test
    void restoresMarketplaceTemplateAsEnabled() {
        authenticate(1L, Set.of("ADMIN"));
        WorkflowModels.MarketplaceTemplateDraft draft = new WorkflowModels.MarketplaceTemplateDraft(
            "N8N", "n8n-nodes-base.redis", "1", "n8n", "a".repeat(64), "N8N_REDIS", "Redis", "",
            "REDIS_COMMAND", "DATA_STORAGE", new ObjectMapper().createObjectNode());
        Long id = service.importMarketplaceTemplate(draft).templateId();
        service.deleteTemplate(id);

        WorkflowModels.MarketplaceTemplatePersistence restored = service.importMarketplaceTemplate(draft);

        assertEquals("RESTORED", restored.status());
        assertTrue(service.template(id).enabled());
    }

    /** 导入状态查询包含停用但未删除模板，并排除已经软删除的组件。 */
    @Test
    void listsOnlyNonVoidedMarketplaceTemplateFingerprints() {
        authenticate(1L, Set.of("ADMIN"));
        WorkflowModels.MarketplaceTemplateDraft active = new WorkflowModels.MarketplaceTemplateDraft(
            "N8N", "n8n-nodes-example/action", "1", "vendor", "a".repeat(64), "N8N_ACTION", "Action", "",
            "PLUGIN_ACTION", "NETWORK_API", new ObjectMapper().createObjectNode());
        WorkflowModels.MarketplaceTemplateDraft deleted = new WorkflowModels.MarketplaceTemplateDraft(
            "N8N", "n8n-nodes-example/trigger", "1", "vendor", "b".repeat(64), "N8N_TRIGGER", "Trigger", "",
            "PLUGIN_TRIGGER", "TRIGGER", new ObjectMapper().createObjectNode());
        Long activeId = service.importMarketplaceTemplate(active).templateId();
        Long deletedId = service.importMarketplaceTemplate(deleted).templateId();
        service.updateTemplate(activeId, new WorkflowModels.NodeTemplateCommand(
            "IGNORED", "Action", "PLUGIN_ACTION", "", new ObjectMapper().createObjectNode(), false,
            "N8N", "NETWORK_API"));
        service.deleteTemplate(deletedId);

        assertEquals(Map.of("n8n-nodes-example/action", "a".repeat(64)),
            service.activeMarketplaceTemplateFingerprints("N8N"));
    }

    /** 市场模板的来源、编码和节点类型必须由后端锁定，不能通过更新接口伪造。 */
    @Test
    void locksImportedTemplateIdentityOnUpdate() {
        authenticate(1L, Set.of("ADMIN"));
        WorkflowModels.MarketplaceTemplateDraft draft = new WorkflowModels.MarketplaceTemplateDraft(
            "N8N", "n8n-nodes-base.redis", "1", "n8n", "a".repeat(64), "N8N_REDIS", "Redis", "",
            "REDIS_COMMAND", "DATA_STORAGE", new ObjectMapper().createObjectNode());
        Long id = service.importMarketplaceTemplate(draft).templateId();

        WorkflowModels.NodeTemplateView updated = service.updateTemplate(id, new WorkflowModels.NodeTemplateCommand(
            "FORGED", "Renamed", "HTTP", "", new ObjectMapper().createObjectNode(), true, "DIFY", "NETWORK_API"));

        assertEquals("N8N_REDIS", updated.code());
        assertEquals("REDIS_COMMAND", updated.nodeType());
        assertEquals("N8N", updated.source());
        assertEquals("Renamed", updated.name());
    }

    /** 市场指纹变化必须先提示，确认后才重置配置、更新版本并启用。 */
    @Test
    void requiresConfirmationBeforeReplacingChangedMarketplaceTemplate() {
        authenticate(1L, Set.of("ADMIN"));
        WorkflowModels.MarketplaceTemplateDraft first = new WorkflowModels.MarketplaceTemplateDraft(
            "DIFY", "langgenius/tavily/tavily_search", "1", "langgenius", "a".repeat(64), "DIFY_TAVILY", "Tavily", "",
            "TAVILY_TOOL", "NETWORK_API", new ObjectMapper().createObjectNode().put("operation", "SEARCH"));
        Long id = service.importMarketplaceTemplate(first).templateId();
        WorkflowModels.MarketplaceTemplateDraft changed = new WorkflowModels.MarketplaceTemplateDraft(
            "DIFY", "langgenius/tavily/tavily_search", "2", "langgenius", "b".repeat(64), "DIFY_TAVILY", "Tavily v2", "",
            "TAVILY_TOOL", "NETWORK_API", new ObjectMapper().createObjectNode().put("operation", "SEARCH").put("maxResults", 5));

        assertEquals("UPDATE_AVAILABLE", service.importMarketplaceTemplates(java.util.List.of(changed), false).get(0).status());
        assertEquals("1", service.template(id).externalVersion());
        assertTrue(service.template(id).enabled());
        assertEquals("UPDATED", service.importMarketplaceTemplates(java.util.List.of(changed), true).get(0).status());
        assertEquals("2", service.template(id).externalVersion());
        assertTrue(service.template(id).enabled());
    }

    /** 通用创建接口不能伪造 n8n 或 Dify 市场来源。 */
    @Test
    void rejectsForgedMarketplaceSourceOnGenericCreate() {
        assertThrows(BusinessException.class, () -> service.createTemplate(new WorkflowModels.NodeTemplateCommand(
            "FORGED", "Forged", "HTTP", "", new ObjectMapper().createObjectNode(), true, "DIFY", "NETWORK_API")));
    }

    /** 设置交互用户身份。 */
    private void authenticate(Long userId, Set<String> roles) {
        AuthContext.set(new AuthUser(userId, "user" + userId, roles, Set.of(), AuthenticationType.TOKEN, null, null));
    }
}
