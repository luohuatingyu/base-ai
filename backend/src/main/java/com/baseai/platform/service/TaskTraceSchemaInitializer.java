package com.baseai.platform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 任务追踪表结构初始化器
 *
 * <p>该组件在应用启动时自动执行，负责初始化和更新任务追踪相关的数据库表结构。
 * 主要功能包括：
 * <ul>
 *   <li>为已存在的 task_trace 表动态添加缺失的字段</li>
 *   <li>创建 task_trace_python 表用于记录Python任务执行追踪信息</li>
 * </ul>
 *
 * <p>使用 @Order(Ordered.HIGHEST_PRECEDENCE) 确保该初始化器在应用启动时最先执行，
 * 保证数据库表结构在其他业务逻辑执行前已经准备就绪。
 *
 * @author baseai-platform
 * @since 1.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TaskTraceSchemaInitializer implements ApplicationRunner {
    /**
     * MySQL数据库操作模板
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造函数，注入MySQL数据库操作模板
     *
     * @param jdbcTemplate MySQL数据库的JdbcTemplate实例，用于执行数据库操作
     */
    public TaskTraceSchemaInitializer(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 应用启动时执行的数据库表结构初始化方法
     *
     * <p>该方法在Spring Boot应用启动完成后自动执行，负责完成以下两个核心任务：
     * <ol>
     *   <li>检查并补全task_trace表的字段：遍历预定义的字段列表，检查每个字段是否存在，
     *       若不存在则动态添加该字段</li>
     *   <li>创建task_trace_python表：如果表不存在则创建，用于记录Python任务的执行追踪信息</li>
     * </ol>
     *
     * <p>字段补全说明：
     * <ul>
     *   <li>trigger_entry: 触发入口标识</li>
     *   <li>request_method: HTTP请求方法</li>
     *   <li>request_params_json: 请求参数JSON</li>
     *   <li>request_headers_json: 请求头JSON</li>
     *   <li>java_instance_id: Java实例ID</li>
     *   <li>cancellation_reason: 取消原因</li>
     *   <li>cancel_requested_at: 请求取消时间</li>
     *   <li>python_trace_count: Python追踪记录数量</li>
     *   <li>heartbeat_at: 心跳时间</li>
     *   <li>version: 版本号（用于乐观锁）</li>
     *   <li>finished_reason: 完成原因</li>
     *   <li>force_terminated_by: 强制终止操作人ID</li>
     *   <li>force_terminated_at: 强制终止时间</li>
     *   <li>force_terminate_reason: 强制终止原因</li>
     *   <li>created_at: 创建时间</li>
     * </ul>
     *
     * @param arguments 应用启动参数（本方法中未使用）
     */
    @Override
    public void run(ApplicationArguments arguments) {
        // 定义需要补全的字段映射：字段名 -> 字段类型及约束
        Map<String, String> columns = Map.ofEntries(
            Map.entry("trigger_entry", "VARCHAR(64)"),
            Map.entry("request_method", "VARCHAR(16)"),
            Map.entry("request_params_json", "MEDIUMTEXT"),
            Map.entry("request_headers_json", "MEDIUMTEXT"),
            Map.entry("java_instance_id", "VARCHAR(100)"),
            Map.entry("cancellation_reason", "VARCHAR(500)"),
            Map.entry("cancel_requested_at", "TIMESTAMP(6) NULL")
            ,Map.entry("python_trace_count", "INT NOT NULL DEFAULT 0")
            ,Map.entry("heartbeat_at", "TIMESTAMP(6) NULL")
            ,Map.entry("version", "BIGINT NOT NULL DEFAULT 0")
            ,Map.entry("finished_reason", "VARCHAR(100)")
            ,Map.entry("force_terminated_by", "BIGINT")
            ,Map.entry("force_terminated_at", "TIMESTAMP(6) NULL")
            ,Map.entry("force_terminate_reason", "VARCHAR(500)")
            ,Map.entry("created_at", "TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
        );

        // 遍历每个字段，检查并添加缺失的字段
        columns.forEach((name, type) -> {
            // 查询information_schema确认字段是否存在于task_trace表中
            Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'task_trace' AND column_name = ?
                """, Integer.class, name);

            // 如果字段不存在（count为0），则执行ALTER TABLE添加该字段
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE task_trace ADD COLUMN " + name + " " + type);
            }
        });

        // 创建task_trace_python表（如果不存在）
        // 该表用于记录Python任务的执行追踪信息，与task_trace表通过parent_trace_id关联
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS task_trace_python (
                python_trace_id VARCHAR(64) PRIMARY KEY,
                parent_trace_id VARCHAR(32) NOT NULL,
                worker_endpoint VARCHAR(255) NOT NULL,
                worker_instance_id VARCHAR(100),
                status VARCHAR(24) NOT NULL,
                heartbeat_at TIMESTAMP(6) NULL,
                error_message VARCHAR(1000),
                finished_reason VARCHAR(100),
                cancel_requested_at TIMESTAMP(6) NULL,
                force_terminated_by BIGINT,
                force_terminated_at TIMESTAMP(6) NULL,
                force_terminate_reason VARCHAR(500),
                created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                started_at TIMESTAMP(6) NULL,
                finished_at TIMESTAMP(6) NULL,
                INDEX idx_task_trace_python_parent (parent_trace_id, created_at),
                INDEX idx_task_trace_python_status (status, heartbeat_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
    }
}
