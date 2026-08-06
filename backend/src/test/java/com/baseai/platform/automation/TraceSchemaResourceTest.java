package com.baseai.platform.automation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSchemaResourceTest {

    /** PostgreSQL Flyway 首版迁移应使用 Trace ID 关联执行日志。 */
    @Test
    void usesTraceIdForExecutionLogs() throws Exception {
        String schema = new ClassPathResource("db/migration/postgresql/V1__create_api_trigger_schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("automation_api_trigger_log"));
        assertTrue(schema.contains("trace_id VARCHAR(32)"));
    }

    /** MySQL Flyway 迁移必须同时覆盖 JPA 平台表、追踪表和旧密钥修复。 */
    @Test
    void containsCompleteMysqlMigrationChain() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V1__create_platform_schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        String repair = new ClassPathResource("db/migration/mysql/V2__repair_api_key_encrypted_secret.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS sys_user"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS sys_api_key"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS task_trace"));
        assertTrue(repair.contains("secret_encrypted IS NULL"));
        assertTrue(repair.contains("secret_encrypted TEXT NOT NULL"));
    }

    /** MySQL 迁移应将历史模型类型转换为当前标准值，并保留已规范及自定义类型。 */
    @Test
    void normalizesLegacyModelTypesWithoutChangingCanonicalValues() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:legacy_model_types;MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE sys_llm_model (id BIGINT PRIMARY KEY, model_type VARCHAR(1000) NOT NULL)");
                statement.executeUpdate("INSERT INTO sys_llm_model VALUES "
                    + "(1, 'TEXT'), (2, ' text '), (3, 'VISION'), (4, 'text_model'), "
                    + "(5, 'vision_model'), (6, 'text_model,vision_model'), (7, 'audio_model')");
            }

            ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/mysql/V3__normalize_legacy_llm_model_types.sql"));

            Map<Long, String> actual = new LinkedHashMap<>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT id, model_type FROM sys_llm_model ORDER BY id")) {
                while (rows.next()) actual.put(rows.getLong("id"), rows.getString("model_type"));
            }
            assertEquals(Map.of(
                1L, "text_model",
                2L, "text_model",
                3L, "vision_model",
                4L, "text_model",
                5L, "vision_model",
                6L, "text_model,vision_model",
                7L, "audio_model"
            ), actual);
        }
    }
}
