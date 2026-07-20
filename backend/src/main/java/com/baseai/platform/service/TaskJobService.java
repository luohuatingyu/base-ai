package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.job.JobRuntimeRegistry;
import com.baseai.platform.job.JobSnapshot;
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
    private final JobRuntimeRegistry runtimeRegistry;
    private final String instanceId;

    public TaskJobService(@Qualifier("systemJdbcTemplate") JdbcTemplate jdbcTemplate,
                          JobRuntimeRegistry runtimeRegistry, PlatformProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeRegistry = runtimeRegistry;
        this.instanceId = properties.getPythonWorker().getJavaInstanceId();
    }

    /** 为前端长耗时请求预留任务编号。 */
    public String reserve(Long ownerUserId) {
        String jobId = newJobId();
        jdbcTemplate.update("""
            INSERT INTO task_job(job_id, owner_user_id, task_type, trigger_entry, status, started_at, java_instance_id)
            VALUES (?, ?, '待绑定任务', 'RESERVATION', 'RESERVED', ?, ?)
            """, jobId, ownerUserId, Timestamp.from(Instant.now()), instanceId);
        return jobId;
    }

    /** 绑定调用方预留编号，或创建新的运行任务。 */
    public String bindOrCreate(String reservedJobId, Long ownerUserId, String taskType, String triggerEntry,
                               String requestMethod, String requestPath, JobSnapshot snapshot) {
        if (reservedJobId != null && !reservedJobId.isBlank()) {
            int updated = jdbcTemplate.update("""
                UPDATE task_job SET task_type=?, trigger_entry=?, status='RUNNING', request_method=?, request_path=?,
                    request_params_json=?, request_headers_json=?, java_instance_id=?, started_at=?
                WHERE job_id=? AND owner_user_id=? AND status='RESERVED'
                """, taskType, triggerEntry, requestMethod, requestPath, snapshot.paramsJson(), snapshot.headersJson(),
                instanceId, Timestamp.from(Instant.now()), reservedJobId.trim(), ownerUserId);
            if (updated == 0) throw new BusinessException("预留任务编号无效或已被使用");
            return reservedJobId.trim();
        }
        String jobId = newJobId();
        jdbcTemplate.update("""
            INSERT INTO task_job(job_id, owner_user_id, task_type, trigger_entry, status, request_path,
                request_method, request_params_json, request_headers_json, java_instance_id, started_at)
            VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?)
            """, jobId, ownerUserId, taskType, triggerEntry, requestPath, requestMethod,
            snapshot.paramsJson(), snapshot.headersJson(), instanceId, Timestamp.from(Instant.now()));
        return jobId;
    }

    /** 将运行任务标记为成功。 */
    public void markSuccess(String jobId) {
        jdbcTemplate.update("UPDATE task_job SET status='SUCCESS', finished_at=? WHERE job_id=? AND status='RUNNING'",
            Timestamp.from(Instant.now()), jobId);
    }

    /** 将运行任务标记为失败。 */
    public void markFailed(String jobId, String message) {
        jdbcTemplate.update("""
            UPDATE task_job SET status='FAILED', error_message=?, finished_at=?
            WHERE job_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')
            """, truncate(message, 1000), Timestamp.from(Instant.now()), jobId);
    }

    /** 完成已发出取消请求的任务。 */
    public void completeCancellation(String jobId) {
        jdbcTemplate.update("""
            UPDATE task_job SET status='CANCELLED', finished_at=?
            WHERE job_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')
            """, Timestamp.from(Instant.now()), jobId);
    }

    /** 查询当前用户可见的任务列表并支持组合过滤。 */
    public List<Map<String, Object>> jobs(Long userId, boolean admin, String status, String taskType, String triggerEntry) {
        StringBuilder sql = new StringBuilder("""
            SELECT job_id, owner_user_id, task_type, trigger_entry, status, request_path, request_method,
                error_message, cancellation_reason, started_at, finished_at
            FROM task_job WHERE 1=1
            """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (!admin) { sql.append(" AND owner_user_id=?"); args.add(userId); }
        if (hasText(status)) { sql.append(" AND status=?"); args.add(status.trim().toUpperCase()); }
        if (hasText(taskType)) { sql.append(" AND task_type=?"); args.add(taskType.trim()); }
        if (hasText(triggerEntry)) { sql.append(" AND trigger_entry=?"); args.add(triggerEntry.trim()); }
        sql.append(" ORDER BY started_at DESC LIMIT 500");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 查询单个任务详情并执行归属校验。 */
    public Map<String, Object> get(String jobId, Long userId, boolean admin) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM task_job WHERE job_id=?", jobId);
        if (rows.isEmpty()) throw BusinessException.notFound("任务不存在");
        verifyOwner(rows.get(0), userId, admin);
        return rows.get(0);
    }

    /** 查询正在执行或等待取消的任务。 */
    public List<Map<String, Object>> running(Long userId, boolean admin) {
        return jobs(userId, admin, null, null, null).stream()
            .filter(row -> List.of("RUNNING", "CANCEL_REQUESTED").contains(String.valueOf(row.get("status"))))
            .toList();
    }

    /** 查询系统已出现的任务类型。 */
    public List<String> taskTypes() {
        return jdbcTemplate.queryForList("SELECT DISTINCT task_type FROM task_job WHERE task_type IS NOT NULL ORDER BY task_type", String.class);
    }

    /** 查询系统已出现的触发入口。 */
    public List<String> triggerEntries() {
        return jdbcTemplate.queryForList("SELECT DISTINCT trigger_entry FROM task_job WHERE trigger_entry IS NOT NULL ORDER BY trigger_entry", String.class);
    }

    /** 查询指定任务的统一 Java/Python 日志。 */
    public List<Map<String, Object>> logs(String jobId, Long userId, boolean admin) {
        get(jobId, userId, admin);
        return jdbcTemplate.queryForList("""
            SELECT id, job_id, python_job_id, source, level, logger_name, message, thread_name, throwable, logged_at
            FROM task_job_log WHERE job_id=? ORDER BY id
            """, jobId);
    }

    /** 请求协作取消并中断当前实例中的运行资源。 */
    public Map<String, Object> cancel(String jobId, Long userId, boolean admin, String reason) {
        Map<String, Object> job = get(jobId, userId, admin);
        String status = String.valueOf(job.get("status"));
        if (!List.of("RUNNING", "CANCEL_REQUESTED").contains(status)) throw new BusinessException("当前任务不可取消");
        jdbcTemplate.update("""
            UPDATE task_job SET status='CANCEL_REQUESTED', cancellation_reason=?, cancel_requested_at=? WHERE job_id=?
            """, truncate(reason, 500), Timestamp.from(Instant.now()), jobId);
        runtimeRegistry.cancel(jobId);
        return get(jobId, userId, admin);
    }

    /** 管理员强制中断当前实例任务并直接落取消终态。 */
    public Map<String, Object> forceTerminate(String jobId, Long userId, boolean admin, String reason) {
        if (!admin) throw BusinessException.forbidden("仅管理员可强制终止任务");
        get(jobId, userId, true);
        runtimeRegistry.cancel(jobId);
        jdbcTemplate.update("""
            UPDATE task_job SET status='CANCELLED', cancellation_reason=?, cancel_requested_at=?, finished_at=? WHERE job_id=?
            """, truncate(reason, 500), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), jobId);
        runtimeRegistry.remove(jobId);
        return get(jobId, userId, true);
    }

    /** 校验任务归属关系。 */
    private void verifyOwner(Map<String, Object> job, Long userId, boolean admin) {
        Object rawOwner = job.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("owner_user_id"))
            .map(Map.Entry::getValue).findFirst().orElse(null);
        if (!admin && (!(rawOwner instanceof Number owner) || owner.longValue() != userId)) {
            throw BusinessException.forbidden("无权访问该任务");
        }
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String truncate(String value, int length) {
        String safe = value == null || value.isBlank() ? "用户请求取消" : value.trim();
        return safe.substring(0, Math.min(length, safe.length()));
    }
    private String newJobId() { return UUID.randomUUID().toString().replace("-", ""); }
}
