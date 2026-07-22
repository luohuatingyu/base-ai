package com.baseai.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTraceSchemaInitializerTest {

    /** Trace 任务表应补齐调度字段，重复初始化不得重复添加字段。 */
    @Test
    void completesTraceSchemaIdempotently() {
        SchemaJdbcTemplate jdbc = new SchemaJdbcTemplate();
        TaskTraceSchemaInitializer initializer = new TaskTraceSchemaInitializer(jdbc);

        initializer.run(null);
        long firstAlterCount = jdbc.statements.stream().filter(sql -> sql.startsWith("ALTER TABLE")).count();
        initializer.run(null);
        long secondAlterCount = jdbc.statements.stream().filter(sql -> sql.startsWith("ALTER TABLE")).count();

        assertTrue(jdbc.columns.containsAll(Set.of(
            "trigger_entry", "request_method", "request_params_json", "request_headers_json",
            "java_instance_id", "cancellation_reason", "cancel_requested_at", "python_trace_count",
            "heartbeat_at", "version", "finished_reason", "force_terminated_by",
            "force_terminated_at", "force_terminate_reason", "created_at"
        )));
        assertEquals(firstAlterCount, secondAlterCount);
        assertTrue(jdbc.statements.stream().anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS task_trace_python")));
    }

    /** 最小化模拟字段元数据查询和 DDL 副作用。 */
    private static final class SchemaJdbcTemplate extends JdbcTemplate {
        private final Set<String> columns = new HashSet<>(Set.of("trace_id"));
        private final List<String> statements = new ArrayList<>();

        /** 返回 Trace 任务表中指定字段是否存在。 */
        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(Integer.valueOf(columns.contains(String.valueOf(args[0])) ? 1 : 0));
        }

        /** 记录 DDL 并应用新增字段副作用。 */
        @Override
        public void execute(String sql) {
            statements.add(sql);
            if (sql.startsWith("ALTER TABLE task_trace ADD COLUMN ")) {
                String[] parts = sql.trim().split("\\s+");
                columns.add(parts[5]);
            }
        }
    }
}
