package com.baseai.platform.automation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSchemaResourceTest {

    /** 接口触发执行日志应使用 Trace ID 关联链路。 */
    @Test
    void usesTraceIdForExecutionLogs() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V1__create_platform_schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("automation_api_trigger_log"));
        assertTrue(schema.contains("trace_id VARCHAR(32) NULL"));
    }

    /** MySQL 基线迁移必须同时覆盖 JPA 平台表、追踪表和密钥密文列。 */
    @Test
    void containsCompleteMysqlBaselineSchema() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V1__create_platform_schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS sys_user"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS sys_api_key"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS task_trace"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS task_trace_python"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trace_log"));
        assertTrue(schema.contains("secret_encrypted TEXT NOT NULL"));
    }
}
