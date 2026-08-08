package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowSchemaResourceTest {
    /** MySQL V4 必须包含模板、版本、运行和节点日志，并初始化全部节点类型。 */
    @Test
    void containsVersionedWorkflowSchemaAndBuiltInNodes() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V4__create_workflow_schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        for (String table : new String[]{"workflow_node_template", "workflow_definition", "workflow_version",
            "workflow_run", "workflow_node_run"}) assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table));
        for (String type : new String[]{"START", "END", "LLM", "HTTP", "AGENT", "CONDITION", "ITERATION", "LOOP"}) {
            assertTrue(schema.contains("'" + type + "'"));
        }
        assertTrue(schema.contains("template_snapshot_json"));
        assertTrue(schema.contains("input_encrypted"));
        assertTrue(schema.contains("output_encrypted"));
    }

    /** MySQL V5 必须兼容仍保留旧 API Key 限流列的历史数据库。 */
    @Test
    void relaxesLegacyApiKeyRateLimitColumn() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V5__relax_legacy_api_key_rate_limit.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("MODIFY COLUMN rate_limit_per_minute INT NULL"));
    }

    /** MySQL V6 必须包含连接、等待和触发去重状态，并初始化全部新增原生节点。 */
    @Test
    void containsNativeWorkflowExtensionSchemaAndNodes() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V6__extend_native_workflow_nodes.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        for (String table : new String[]{"workflow_connection", "workflow_wait_state", "workflow_trigger_delivery"}) {
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table));
        }
        for (String type : WorkflowNodeTypes.ALL) {
            if (!Set.of("START", "END", "LLM", "HTTP", "AGENT", "CONDITION", "ITERATION", "LOOP").contains(type)) {
                assertTrue(schema.contains("'" + type + "'"), type);
            }
        }
        assertTrue(schema.contains("state_encrypted"));
        assertTrue(schema.contains("uk_workflow_trigger_event"));
    }

    /** MySQL V8 必须把模板来源约束为 Base AI 原生或用户自定义。 */
    @Test
    void normalizesNativeWorkflowTemplateSources() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V8__normalize_native_workflow_template_sources.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("system_template = b'1' THEN 'SYSTEM' ELSE 'CUSTOM'"));
        assertTrue(schema.contains("DEFAULT 'CUSTOM'"));
    }

    /** MySQL V9 必须恢复系统、n8n、Dify 三种后台可配置来源并兼容已执行 V8 的数据库。 */
    @Test
    void restoresConfigurableWorkflowTemplateSources() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V9__restore_configurable_workflow_template_sources.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("DEFAULT 'SYSTEM'"));
        assertTrue(schema.contains("NOT IN ('SYSTEM', 'N8N', 'DIFY')"));
    }

    /** MySQL V7 必须增加来源和功能分类，并将历史模板准确回填为系统来源。 */
    @Test
    void categorizesExistingWorkflowNodeTemplates() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V7__categorize_workflow_node_templates.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("template_source VARCHAR(16) NOT NULL DEFAULT 'SYSTEM'"));
        assertTrue(schema.contains("functional_category VARCHAR(32) NOT NULL DEFAULT 'BASIC'"));
        assertTrue(schema.contains("SET template_source = 'SYSTEM'"));
        for (String category : WorkflowTemplateCatalog.CATEGORIES) assertTrue(schema.contains("'" + category + "'"), category);
        for (String type : WorkflowNodeTypes.ALL) assertTrue(schema.contains("'" + type + "'"), type);
    }
}
