package com.baseai.platform.automation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

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
}
