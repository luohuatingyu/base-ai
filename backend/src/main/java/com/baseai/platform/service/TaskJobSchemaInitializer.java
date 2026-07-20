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
            ,Map.entry("python_job_count", "INT NOT NULL DEFAULT 0")
            ,Map.entry("heartbeat_at", "TIMESTAMP(6) NULL")
            ,Map.entry("version", "BIGINT NOT NULL DEFAULT 0")
            ,Map.entry("finished_reason", "VARCHAR(100)")
            ,Map.entry("force_terminated_by", "BIGINT")
            ,Map.entry("force_terminated_at", "TIMESTAMP(6) NULL")
            ,Map.entry("force_terminate_reason", "VARCHAR(500)")
            ,Map.entry("created_at", "TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
        );
        columns.forEach((name, type) -> {
            Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'task_job' AND column_name = ?
                """, Integer.class, name);
            if (count != null && count == 0) jdbcTemplate.execute("ALTER TABLE task_job ADD COLUMN " + name + " " + type);
        });
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS task_job_python (
                python_job_id VARCHAR(64) PRIMARY KEY, parent_job_id VARCHAR(32) NOT NULL,
                worker_endpoint VARCHAR(255) NOT NULL, worker_instance_id VARCHAR(100), status VARCHAR(24) NOT NULL,
                heartbeat_at TIMESTAMP(6) NULL, error_message VARCHAR(1000), finished_reason VARCHAR(100),
                cancel_requested_at TIMESTAMP(6) NULL, force_terminated_by BIGINT, force_terminated_at TIMESTAMP(6) NULL,
                force_terminate_reason VARCHAR(500), created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                started_at TIMESTAMP(6) NULL, finished_at TIMESTAMP(6) NULL,
                INDEX idx_task_job_python_parent (parent_job_id, created_at),
                INDEX idx_task_job_python_status (status, heartbeat_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }
}
