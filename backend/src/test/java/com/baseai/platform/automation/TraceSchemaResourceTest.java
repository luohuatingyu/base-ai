package com.baseai.platform.automation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSchemaResourceTest {

    /** PostgreSQL 初始化脚本应使用 Trace ID 关联执行日志。 */
    @Test
    void usesTraceIdForExecutionLogs() throws Exception {
        String schema = new ClassPathResource("api-trigger-schema.sql").getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("automation_api_trigger_log"));
        assertTrue(schema.contains("trace_id VARCHAR(32)"));
    }
}
