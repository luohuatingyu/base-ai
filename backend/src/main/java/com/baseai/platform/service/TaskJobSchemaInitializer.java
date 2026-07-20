package com.baseai.platform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TaskJobSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public TaskJobSchemaInitializer(@Qualifier("systemJdbcTemplate") JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** 为已存在的系统任务表补齐 AOP 调度字段。 */
    @Override
    public void run(ApplicationArguments arguments) {
        Map<String, String> columns = Map.ofEntries(
            Map.entry("trigger_entry", "VARCHAR(64)"),
            Map.entry("request_method", "VARCHAR(16)"),
            Map.entry("request_params_json", "MEDIUMTEXT"),
            Map.entry("request_headers_json", "MEDIUMTEXT"),
            Map.entry("java_instance_id", "VARCHAR(100)"),
            Map.entry("cancellation_reason", "VARCHAR(500)"),
            Map.entry("cancel_requested_at", "TIMESTAMP(6) NULL")
        );
        columns.forEach((name, type) -> {
            Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'task_job' AND column_name = ?
                """, Integer.class, name);
            if (count != null && count == 0) jdbcTemplate.execute("ALTER TABLE task_job ADD COLUMN " + name + " " + type);
        });
    }
}
