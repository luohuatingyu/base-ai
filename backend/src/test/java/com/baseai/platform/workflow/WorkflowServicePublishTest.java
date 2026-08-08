package com.baseai.platform.workflow;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WorkflowServicePublishTest {
    private JdbcTemplate jdbcTemplate;
    private WorkflowService service;

    /** 为每个发布场景创建隔离版本表和真实节点配置校验器。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-publish-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_definition(
              id BIGINT PRIMARY KEY,code VARCHAR(80),name VARCHAR(120),description VARCHAR(500),status VARCHAR(20),
              current_version_id BIGINT,published_version_id BIGINT,revision BIGINT,enabled BOOLEAN,owner_user_id BIGINT,
              voided BOOLEAN,created_at TIMESTAMP,updated_at TIMESTAMP)
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_version(
              id BIGINT PRIMARY KEY,workflow_id BIGINT,version_number INT,graph_json CLOB,input_schema_json CLOB,
              template_snapshot_json CLOB,created_at TIMESTAMP)
            """);
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        service = new WorkflowService(jdbcTemplate, objectMapper, new ConfigCryptoService(properties),
            mock(WorkflowGraphValidator.class), new WorkflowNodeConfigValidator(objectMapper), mock(WorkflowConnectionService.class));
    }

    /** 当前版本缺少运行必填配置时必须保持草稿状态且不写入发布版本。 */
    @Test
    void rejectsPublishingIncompleteNodeConfiguration() {
        insertWorkflow("""
            {"nodes":[{"id":"start","type":"START"},{"id":"http","type":"HTTP","data":{"label":"调用接口","config":{}}},{"id":"end","type":"END"}],
             "edges":[{"id":"a","source":"start","target":"http"},{"id":"b","source":"http","target":"end"}]}
            """, "{}");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.publish(1L));

        assertEquals("workflow.nodeConfigRequired", exception.getMessageKey());
        assertEquals("DRAFT", jdbcTemplate.queryForObject("SELECT status FROM workflow_definition WHERE id=1", String.class));
        assertNull(jdbcTemplate.queryForObject("SELECT published_version_id FROM workflow_definition WHERE id=1", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT revision FROM workflow_definition WHERE id=1", Long.class));
    }

    /** 模板快照补齐必填字段后发布应原子更新当前版本和状态。 */
    @Test
    void publishesWhenEffectiveConfigurationIsComplete() {
        insertWorkflow("""
            {"nodes":[{"id":"start","type":"START"},{"id":"http","type":"HTTP","data":{"config":{}}},{"id":"end","type":"END"}],
             "edges":[{"id":"a","source":"start","target":"http"},{"id":"b","source":"http","target":"end"}]}
            """, "{\"http\":{\"config\":{\"method\":\"GET\",\"url\":\"https://example.test\"}}}");

        WorkflowModels.WorkflowView published = service.publish(1L);

        assertEquals("PUBLISHED", published.status());
        assertEquals(10L, published.publishedVersionId());
        assertEquals(2L, published.revision());
    }

    /** 插入一个当前版本工作流及对应不可变模板快照。 */
    private void insertWorkflow(String graph, String snapshots) {
        jdbcTemplate.update("""
            INSERT INTO workflow_definition VALUES (1,'ORDERS','Orders','', 'DRAFT',10,NULL,1,true,7,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """);
        jdbcTemplate.update("""
            INSERT INTO workflow_version VALUES (10,1,1,?,'{}',?,CURRENT_TIMESTAMP)
            """, graph, snapshots);
    }
}
