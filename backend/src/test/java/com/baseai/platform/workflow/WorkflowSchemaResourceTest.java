package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

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
}
