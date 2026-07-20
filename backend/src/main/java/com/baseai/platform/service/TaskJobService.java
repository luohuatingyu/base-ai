package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.job.JobRuntimeRegistry;
import com.baseai.platform.job.JobSnapshot;
import com.baseai.platform.job.TaskTypeRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class TaskJobService {
    private final JdbcTemplate jdbcTemplate;
    private final JobRuntimeRegistry runtimeRegistry;
    private final RestClient workerClient;
    private final String instanceId;
    private final TaskTypeRegistry taskTypeRegistry;

    public TaskJobService(@Qualifier("systemJdbcTemplate") JdbcTemplate jdbcTemplate, JobRuntimeRegistry runtimeRegistry,
                          @Qualifier("pythonWorkerRestClient") RestClient workerClient, PlatformProperties properties,
                          TaskTypeRegistry taskTypeRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeRegistry = runtimeRegistry;
        this.workerClient = workerClient;
        this.instanceId = properties.getPythonWorker().getJavaInstanceId();
        this.taskTypeRegistry = taskTypeRegistry;
    }

    /** 为前端长耗时请求预留任务编号。 */
    public String reserve(Long ownerUserId) {
        String jobId = newJobId();
        jdbcTemplate.update("""
            INSERT INTO task_job(job_id, owner_user_id, task_type, trigger_entry, status, started_at, heartbeat_at, java_instance_id)
            VALUES (?, ?, '待绑定任务', 'RESERVATION', 'RESERVED', ?, ?, ?)
            """, jobId, ownerUserId, now(), now(), instanceId);
        return jobId;
    }

    /** 绑定调用方预留编号，或创建新的运行任务。 */
    public String bindOrCreate(String reservedJobId, Long ownerUserId, String taskType, String triggerEntry,
                               String requestMethod, String requestPath, JobSnapshot snapshot) {
        taskTypeRegistry.register(taskType, triggerEntry);
        if (hasText(reservedJobId)) {
            int updated = jdbcTemplate.update("""
                UPDATE task_job SET task_type=?, trigger_entry=?, status='RUNNING', request_method=?, request_path=?,
                    request_params_json=?, request_headers_json=?, java_instance_id=?, started_at=?, heartbeat_at=?, version=version+1
                WHERE job_id=? AND owner_user_id=? AND status='RESERVED'
                """, taskType, triggerEntry, requestMethod, requestPath, snapshot.paramsJson(), snapshot.headersJson(),
                instanceId, now(), now(), reservedJobId.trim(), ownerUserId);
            if (updated == 0) throw new BusinessException("预留任务编号无效或已被使用");
            return reservedJobId.trim();
        }
        String jobId = newJobId();
        jdbcTemplate.update("""
            INSERT INTO task_job(job_id, owner_user_id, task_type, trigger_entry, status, request_path, request_method,
                request_params_json, request_headers_json, java_instance_id, started_at, heartbeat_at)
            VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?)
            """, jobId, ownerUserId, taskType, triggerEntry, requestPath, requestMethod, snapshot.paramsJson(),
            snapshot.headersJson(), instanceId, now(), now());
        return jobId;
    }

    /** 将运行任务标记为成功。 */
    public void markSuccess(String jobId) {
        jdbcTemplate.update("UPDATE task_job SET status='SUCCESS', finished_reason='COMPLETED', finished_at=?, heartbeat_at=?, version=version+1 WHERE job_id=? AND status='RUNNING'", now(), now(), jobId);
    }

    /** 将运行任务标记为失败。 */
    public void markFailed(String jobId, String message) {
        jdbcTemplate.update("""
            UPDATE task_job SET status='FAILED', error_message=?, finished_reason='EXECUTION_FAILED', finished_at=?, heartbeat_at=?, version=version+1
            WHERE job_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')
            """, truncate(message, 1000), now(), now(), jobId);
    }

    /** 完成已发出取消请求的任务。 */
    public void completeCancellation(String jobId) {
        jdbcTemplate.update("""
            UPDATE task_job SET status='CANCELLED', finished_reason='CANCELLED', finished_at=?, heartbeat_at=?, version=version+1
            WHERE job_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')
            """, now(), now(), jobId);
    }

    /** 在调用 Worker 前登记可取消的 Python 子任务。 */
    public void registerPython(String parentJobId, String pythonJobId, String endpoint) {
        if (!hasText(parentJobId)) return;
        jdbcTemplate.update("""
            INSERT INTO task_job_python(python_job_id, parent_job_id, worker_endpoint, status, started_at, heartbeat_at)
            VALUES (?, ?, ?, 'RUNNING', ?, ?)
            """, pythonJobId, parentJobId, endpoint, now(), now());
        jdbcTemplate.update("UPDATE task_job SET python_job_count=python_job_count+1, heartbeat_at=?, version=version+1 WHERE job_id=?", now(), parentJobId);
    }

    /** 接收 Worker 子任务状态和心跳。 */
    public void updatePython(String pythonJobId, String status, String workerInstanceId, String errorMessage) {
        String normalized = status == null ? "RUNNING" : status.toUpperCase(Locale.ROOT);
        boolean terminal = List.of("SUCCESS", "FAILED", "CANCELLED").contains(normalized);
        jdbcTemplate.update("""
            UPDATE task_job_python SET status=?, worker_instance_id=?, heartbeat_at=?, error_message=?,
                finished_reason=?, finished_at=? WHERE python_job_id=?
            """, normalized, workerInstanceId, now(), truncateNullable(errorMessage, 1000),
            terminal ? normalized : null, terminal ? now() : null, pythonJobId);
        jdbcTemplate.update("""
            UPDATE task_job SET heartbeat_at=?, version=version+1
            WHERE job_id=(SELECT parent_job_id FROM task_job_python WHERE python_job_id=?)
            """, now(), pythonJobId);
    }

    /** 查询当前用户可见的任务列表并支持组合过滤。 */
    public List<Map<String, Object>> jobs(Long userId, boolean admin, String status, String taskType, String triggerEntry) {
        StringBuilder sql = new StringBuilder("""
            SELECT job_id, owner_user_id, task_type, trigger_entry, status, request_path, request_method,
                python_job_count, error_message, cancellation_reason, heartbeat_at, started_at, finished_at, finished_reason
            FROM task_job WHERE 1=1
            """);
        ArrayList<Object> args = new ArrayList<>();
        if (!admin) { sql.append(" AND owner_user_id=?"); args.add(userId); }
        if (hasText(status)) { sql.append(" AND status=?"); args.add(status.trim().toUpperCase(Locale.ROOT)); }
        if (hasText(taskType)) { sql.append(" AND task_type=?"); args.add(taskType.trim()); }
        if (hasText(triggerEntry)) { sql.append(" AND trigger_entry=?"); args.add(triggerEntry.trim()); }
        sql.append(" ORDER BY started_at DESC LIMIT 500");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 查询单个任务详情并附带 Python 子任务。 */
    public Map<String, Object> get(String jobId, Long userId, boolean admin) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM task_job WHERE job_id=?", jobId);
        if (rows.isEmpty()) throw BusinessException.notFound("任务不存在");
        verifyOwner(rows.get(0), userId, admin);
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("pythonJobs", jdbcTemplate.queryForList("SELECT * FROM task_job_python WHERE parent_job_id=? ORDER BY created_at", jobId));
        return result;
    }

    public List<Map<String, Object>> running(Long userId, boolean admin) { return jobs(userId, admin, null, null, null).stream().filter(row -> List.of("RUNNING", "CANCEL_REQUESTED").contains(String.valueOf(row.get("status")))).toList(); }
    public List<String> taskTypes() { return jdbcTemplate.queryForList("SELECT DISTINCT task_type FROM task_job WHERE task_type IS NOT NULL ORDER BY task_type", String.class); }
    public List<String> triggerEntries() { return jdbcTemplate.queryForList("SELECT DISTINCT trigger_entry FROM task_job WHERE trigger_entry IS NOT NULL ORDER BY trigger_entry", String.class); }
    public List<TaskTypeRegistry.Metadata> taskMetadata() { return taskTypeRegistry.all(); }

    /** 查询指定任务的统一 Java/Python 日志。 */
    public List<Map<String, Object>> logs(String jobId, Long userId, boolean admin) {
        get(jobId, userId, admin);
        return jdbcTemplate.queryForList("SELECT id, job_id, python_job_id, source, level, logger_name, message, thread_name, throwable, logged_at FROM task_job_log WHERE job_id=? ORDER BY id", jobId);
    }

    /** 请求协作取消并传播到全部 Worker 子任务。 */
    public Map<String, Object> cancel(String jobId, Long userId, boolean admin, String reason) {
        Map<String, Object> job = get(jobId, userId, admin);
        String status = String.valueOf(value(job, "status"));
        if (!List.of("RUNNING", "CANCEL_REQUESTED").contains(status)) throw new BusinessException("当前任务不可取消");
        jdbcTemplate.update("UPDATE task_job SET status='CANCEL_REQUESTED', cancellation_reason=?, cancel_requested_at=?, version=version+1 WHERE job_id=?", truncate(reason, 500), now(), jobId);
        runtimeRegistry.cancel(jobId);
        cancelPythonJobs(jobId, false, userId, reason);
        return get(jobId, userId, admin);
    }

    /** 管理员强制终止父任务及全部 Python 子任务并记录审计。 */
    public Map<String, Object> forceTerminate(String jobId, Long userId, boolean admin, String reason) {
        if (!admin) throw BusinessException.forbidden("仅管理员可强制终止任务");
        get(jobId, userId, true);
        runtimeRegistry.cancel(jobId);
        cancelPythonJobs(jobId, true, userId, reason);
        jdbcTemplate.update("""
            UPDATE task_job SET status='CANCELLED', finished_reason='FORCE_TERMINATED', force_terminated_by=?,
                force_terminated_at=?, force_terminate_reason=?, cancellation_reason=?, cancel_requested_at=?, finished_at=?, version=version+1
            WHERE job_id=?
            """, userId, now(), truncate(reason, 500), truncate(reason, 500), now(), now(), jobId);
        runtimeRegistry.remove(jobId);
        return get(jobId, userId, true);
    }

    /** 向 Worker 发出子任务取消请求。 */
    private void cancelPythonJobs(String jobId, boolean force, Long userId, String reason) {
        List<String> ids = jdbcTemplate.queryForList("SELECT python_job_id FROM task_job_python WHERE parent_job_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')", String.class, jobId);
        for (String pythonJobId : ids) {
            jdbcTemplate.update("UPDATE task_job_python SET status='CANCEL_REQUESTED', cancel_requested_at=? WHERE python_job_id=?", now(), pythonJobId);
            try { workerClient.post().uri("/jobs/{id}/cancel", pythonJobId).retrieve().toBodilessEntity(); } catch (Exception ignored) { }
            if (force) jdbcTemplate.update("""
                UPDATE task_job_python SET status='CANCELLED', finished_reason='FORCE_TERMINATED', force_terminated_by=?,
                    force_terminated_at=?, force_terminate_reason=?, finished_at=? WHERE python_job_id=?
                """, userId, now(), truncate(reason, 500), now(), pythonJobId);
        }
    }

    /** 校验任务归属关系。 */
    private void verifyOwner(Map<String, Object> job, Long userId, boolean admin) {
        Object rawOwner = value(job, "owner_user_id");
        if (!admin && (!(rawOwner instanceof Number owner) || owner.longValue() != userId)) throw BusinessException.forbidden("无权访问该任务");
    }
    private Object value(Map<String, Object> row, String name) { return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null); }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String truncate(String value, int length) { String safe = hasText(value) ? value.trim() : "用户请求取消"; return safe.substring(0, Math.min(length, safe.length())); }
    private String truncateNullable(String value, int length) { return value == null ? null : value.substring(0, Math.min(length, value.length())); }
    private Timestamp now() { return Timestamp.from(Instant.now()); }
    private String newJobId() { return UUID.randomUUID().toString().replace("-", ""); }
}
