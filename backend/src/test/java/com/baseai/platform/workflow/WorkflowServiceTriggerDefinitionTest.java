package com.baseai.platform.workflow;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class WorkflowServiceTriggerDefinitionTest {
    /** 触发器扫描必须合并模板快照和实例覆盖，保证发布版本配置不可变。 */
    @Test
    void mergesTemplateSnapshotIntoPublishedTrigger() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-trigger-definition;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_definition(id BIGINT PRIMARY KEY,code VARCHAR(80),owner_user_id BIGINT,
            published_version_id BIGINT,status VARCHAR(20),enabled BOOLEAN,voided BOOLEAN)
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_version(id BIGINT PRIMARY KEY,workflow_id BIGINT,graph_json CLOB,template_snapshot_json CLOB)
            """);
        jdbcTemplate.update("""
            INSERT INTO workflow_definition VALUES (1,'ORDERS',7,2,'PUBLISHED',true,false)
            """);
        jdbcTemplate.update("""
            INSERT INTO workflow_version VALUES (2,1,?,?)
            """, """
            {"nodes":[{"id":"hook","type":"WEBHOOK_TRIGGER","templateId":9,"config":{"eventName":"order"}},
            {"id":"end","type":"END"}],"edges":[{"id":"edge","source":"hook","target":"end"}]}
            """, """
            {"hook":{"config":{"connectionId":12,"eventName":"default"}}}
            """);
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        WorkflowService service = new WorkflowService(jdbcTemplate, new ObjectMapper(), new ConfigCryptoService(properties),
            mock(WorkflowGraphValidator.class), mock(WorkflowConnectionService.class));

        List<WorkflowModels.TriggerDefinition> definitions = service.triggerDefinitions();

        assertEquals(1, definitions.size());
        assertEquals(12, definitions.get(0).config().path("connectionId").asInt());
        assertEquals("order", definitions.get(0).config().path("eventName").asText());
    }
}
