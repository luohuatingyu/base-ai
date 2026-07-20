package com.baseai.platform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskJobService {
    private final JdbcTemplate jdbcTemplate;

    public TaskJobService(@Qualifier("systemJdbcTemplate") JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** 创建运行中的系统任务。 */
    public String start(Long ownerUserId, String taskType, String requestPath) {
        String jobId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO task_job(job_id, owner_user_id, task_type, status, request_path, started_at) VALUES (?, ?, ?, 'RUNNING', ?, ?)",
            jobId, ownerUserId, taskType, requestPath, Timestamp.from(Instant.now()));
        return jobId;
    }

    /** 标记任务成功完成。 */
    public void success(String jobId) {
        jdbcTemplate.update("UPDATE task_job SET status='SUCCESS', finished_at=? WHERE job_id=?", Timestamp.from(Instant.now()), jobId);
    }

    /** 标记任务失败并保存截断后的错误信息。 */
    public void failed(String jobId, String message) {
        String safeMessage = message == null ? "未知错误" : message.substring(0, Math.min(1000, message.length()));
        jdbcTemplate.update("UPDATE task_job SET status='FAILED', error_message=?, finished_at=? WHERE job_id=?",
            safeMessage, Timestamp.from(Instant.now()), jobId);
    }

    /** 查询当前用户可见任务，管理员可查看全部。 */
    public List<Map<String, Object>> jobs(Long userId, boolean admin) {
        String sql = "SELECT job_id, owner_user_id, task_type, status, request_path, error_message, started_at, finished_at FROM task_job "
            + (admin ? "" : "WHERE owner_user_id = ? ") + "ORDER BY started_at DESC LIMIT 200";
        return admin ? jdbcTemplate.queryForList(sql) : jdbcTemplate.queryForList(sql, userId);
    }

    /** 查询指定任务的统一 Java/Python 日志。 */
    public List<Map<String, Object>> logs(String jobId) {
        return jdbcTemplate.queryForList("SELECT id, job_id, python_job_id, source, level, logger_name, message, thread_name, throwable, logged_at FROM task_job_log WHERE job_id=? ORDER BY id", jobId);
    }
}
