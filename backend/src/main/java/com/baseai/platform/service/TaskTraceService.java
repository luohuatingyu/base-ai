package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.trace.TraceRuntimeRegistry;
import com.baseai.platform.trace.TraceSnapshot;
import com.baseai.platform.trace.TaskTypeRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * 任务追踪服务
 *
 * <p>负责管理任务的完整生命周期，包括任务的创建、状态追踪、取消控制和日志管理。
 * 支持Java任务和Python Worker子任务的统一管理，提供任务列表查询、详情查看、
 * 日志过滤等功能。</p>
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>任务生命周期管理：创建、预留、绑定、完成、失败、取消</li>
 *   <li>Python子任务管理：注册、状态更新、心跳维护</li>
 *   <li>任务查询：支持多条件过滤、分页、权限控制</li>
 *   <li>日志管理：统一查询Java和Python日志，支持时间范围和关键字过滤</li>
 *   <li>取消控制：协作取消和强制终止，支持传播到子任务</li>
 * </ul>
 *
 * @author BaseAI Platform
 * @since 1.0
 */
@Service
public class TaskTraceService {
    /** 数据库操作模板，用于执行SQL查询和更新 */
    private final JdbcTemplate jdbcTemplate;

    /** 运行时任务注册表，用于管理内存中的任务状态和取消信号 */
    private final TraceRuntimeRegistry runtimeRegistry;

    /** Python Worker客户端，用于与Python工作节点通信 */
    private final RestClient workerClient;

    /** 当前Java实例ID，用于标识任务所属的服务实例 */
    private final String instanceId;

    /** 任务类型注册表，维护任务类型和触发入口的元数据 */
    private final TaskTypeRegistry taskTypeRegistry;

    /**
     * 构造函数，注入所需的依赖
     *
     * @param jdbcTemplate MySQL数据库操作模板
     * @param runtimeRegistry 任务运行时注册表
     * @param workerClient Python Worker REST客户端
     * @param properties 平台配置属性
     * @param taskTypeRegistry 任务类型注册表
     */
    public TaskTraceService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate, TraceRuntimeRegistry runtimeRegistry,
                          @Qualifier("pythonWorkerRestClient") RestClient workerClient, PlatformProperties properties,
                          TaskTypeRegistry taskTypeRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeRegistry = runtimeRegistry;
        this.workerClient = workerClient;
        this.instanceId = properties.getPythonWorker().getJavaInstanceId();
        this.taskTypeRegistry = taskTypeRegistry;
    }

    /**
     * 为前端长耗时请求预留 Trace ID
     *
     * <p>在前端发起长耗时请求之前，先调用此方法预留一个Trace ID，
     * 状态为RESERVED。后续可以通过bindOrCreate方法绑定实际任务信息。</p>
     *
     * @param ownerUserId 任务所属用户ID
     * @return 生成的Trace ID（32位无连字符UUID）
     */
    public String reserve(Long ownerUserId) {
        // 生成新的Trace ID
        String traceId = newTraceId();
        // 插入预留记录，状态为RESERVED
        jdbcTemplate.update("""
            INSERT INTO task_trace(trace_id, owner_user_id, task_type, trigger_entry, status, started_at, heartbeat_at, java_instance_id)
            VALUES (?, ?, '待绑定任务', 'RESERVATION', 'RESERVED', ?, ?, ?)
            """, traceId, ownerUserId, now(), now(), instanceId);
        return traceId;
    }

    /**
     * 绑定调用方预留的 Trace ID，或创建新的运行任务
     *
     * <p>如果提供了预留的Trace ID，则将其从RESERVED状态更新为RUNNING状态，
     * 并绑定实际的任务类型、触发入口和请求信息。如果未提供预留ID，则直接创建新任务。</p>
     *
     * @param reservedTraceId 预留的Trace ID（可选，如为空则创建新任务）
     * @param ownerUserId 任务所属用户ID
     * @param taskType 任务类型（如"数据导出"、"模型训练"等）
     * @param triggerEntry 触发入口（如API路径或功能模块名称）
     * @param requestMethod HTTP请求方法（GET、POST等）
     * @param requestPath 请求路径
     * @param snapshot 追踪快照，包含请求参数和请求头信息
     * @return 绑定或创建的Trace ID
     * @throws BusinessException 如果预留ID无效或已被使用
     */
    public String bindOrCreate(String reservedTraceId, Long ownerUserId, String taskType, String triggerEntry,
                               String requestMethod, String requestPath, TraceSnapshot snapshot) {
        // 注册任务类型和触发入口到元数据注册表
        taskTypeRegistry.register(taskType, triggerEntry);

        // 如果提供了预留ID，则绑定到已有记录
        if (hasText(reservedTraceId)) {
            // 更新预留记录，将状态改为RUNNING并填充实际任务信息
            int updated = jdbcTemplate.update("""
                UPDATE task_trace SET task_type=?, trigger_entry=?, status='RUNNING', request_method=?, request_path=?,
                    request_params_json=?, request_headers_json=?, java_instance_id=?, started_at=?, heartbeat_at=?, version=version+1
                WHERE trace_id=? AND owner_user_id=? AND status='RESERVED'
                """, taskType, triggerEntry, requestMethod, requestPath, snapshot.paramsJson(), snapshot.headersJson(),
                instanceId, now(), now(), reservedTraceId.trim(), ownerUserId);
            // 如果没有更新到记录，说明ID无效或已被使用
            if (updated == 0) throw new BusinessException("预留任务编号无效或已被使用");
            return reservedTraceId.trim();
        }

        // 没有预留ID，直接创建新任务
        String traceId = newTraceId();
        jdbcTemplate.update("""
            INSERT INTO task_trace(trace_id, owner_user_id, task_type, trigger_entry, status, request_path, request_method,
                request_params_json, request_headers_json, java_instance_id, started_at, heartbeat_at)
            VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?)
            """, traceId, ownerUserId, taskType, triggerEntry, requestPath, requestMethod, snapshot.paramsJson(),
            snapshot.headersJson(), instanceId, now(), now());
        return traceId;
    }

    /**
     * 将运行中的任务标记为成功
     *
     * <p>更新任务状态为SUCCESS，记录完成原因为COMPLETED，并更新完成时间和心跳时间。
     * 仅对状态为RUNNING的任务生效。</p>
     *
     * @param traceId 任务追踪ID
     */
    public void markSuccess(String traceId) {
        jdbcTemplate.update("UPDATE task_trace SET status='SUCCESS', finished_reason='COMPLETED', finished_at=?, heartbeat_at=?, version=version+1 WHERE trace_id=? AND status='RUNNING'", now(), now(), traceId);
    }

    /**
     * 将运行中的任务标记为失败
     *
     * <p>更新任务状态为FAILED，记录错误消息和完成原因为EXECUTION_FAILED，
     * 并更新完成时间和心跳时间。对状态为RUNNING或CANCEL_REQUESTED的任务生效。</p>
     *
     * @param traceId 任务追踪ID
     * @param message 错误消息（最多保留1000字符）
     */
    public void markFailed(String traceId, String message) {
        jdbcTemplate.update("""
            UPDATE task_trace SET status='FAILED', error_message=?, finished_reason='EXECUTION_FAILED', finished_at=?, heartbeat_at=?, version=version+1
            WHERE trace_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')
            """, truncate(message, 1000), now(), now(), traceId);
    }

    /**
     * 完成已发出取消请求的任务
     *
     * <p>将状态为RUNNING或CANCEL_REQUESTED的任务标记为CANCELLED，
     * 记录完成原因为CANCELLED，并更新完成时间和心跳时间。
     * 通常在任务响应取消信号并完成清理后调用。</p>
     *
     * @param traceId 任务追踪ID
     */
    public void completeCancellation(String traceId) {
        jdbcTemplate.update("""
            UPDATE task_trace SET status='CANCELLED', finished_reason='CANCELLED', finished_at=?, heartbeat_at=?, version=version+1
            WHERE trace_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')
            """, now(), now(), traceId);
    }

    /**
     * 在调用 Worker 前登记可取消的 Python 子任务
     *
     * <p>当Java任务需要调用Python Worker执行子任务时，先调用此方法登记子任务信息，
     * 以便后续可以追踪子任务状态和支持取消操作。同时会增加父任务的子任务计数。</p>
     *
     * @param parentTraceId 父任务追踪ID
     * @param pythonTraceId Python子任务追踪ID
     * @param endpoint Worker端点地址
     */
    public void registerPython(String parentTraceId, String pythonTraceId, String endpoint) {
        // 如果父任务ID为空，不进行注册
        if (!hasText(parentTraceId)) return;

        // 插入Python子任务记录
        jdbcTemplate.update("""
            INSERT INTO task_trace_python(python_trace_id, parent_trace_id, worker_endpoint, status, started_at, heartbeat_at)
            VALUES (?, ?, ?, 'RUNNING', ?, ?)
            """, pythonTraceId, parentTraceId, endpoint, now(), now());

        // 更新父任务的Python子任务计数和心跳时间
        jdbcTemplate.update("UPDATE task_trace SET python_trace_count=python_trace_count+1, heartbeat_at=?, version=version+1 WHERE trace_id=?", now(), parentTraceId);
    }

    /**
     * 接收 Worker 子任务状态和心跳更新
     *
     * <p>Python Worker定期调用此方法报告子任务的状态、心跳和错误信息。
     * 同时会更新父任务的心跳时间，确保父任务感知到子任务的活动状态。</p>
     *
     * @param pythonTraceId Python子任务追踪ID
     * @param status 任务状态（RUNNING/SUCCESS/FAILED/CANCELLED）
     * @param workerInstanceId Worker实例ID
     * @param errorMessage 错误消息（如有）
     */
    public void updatePython(String pythonTraceId, String status, String workerInstanceId, String errorMessage) {
        // 规范化状态值，默认为RUNNING
        String normalized = status == null ? "RUNNING" : status.toUpperCase(Locale.ROOT);
        // 判断是否为终止状态
        boolean terminal = List.of("SUCCESS", "FAILED", "CANCELLED").contains(normalized);

        // 更新Python子任务记录
        jdbcTemplate.update("""
            UPDATE task_trace_python SET status=?, worker_instance_id=?, heartbeat_at=?, error_message=?,
                finished_reason=?, finished_at=? WHERE python_trace_id=?
            """, normalized, workerInstanceId, now(), truncateNullable(errorMessage, 1000),
            terminal ? normalized : null, terminal ? now() : null, pythonTraceId);

        // 更新父任务的心跳时间，表示父任务仍在活动
        jdbcTemplate.update("""
            UPDATE task_trace SET heartbeat_at=?, version=version+1
            WHERE trace_id=(SELECT parent_trace_id FROM task_trace_python WHERE python_trace_id=?)
            """, now(), pythonTraceId);
    }

    /**
     * 查询当前用户可见的任务列表并支持组合过滤
     *
     * <p>提供强大的任务查询功能，支持多种过滤条件的组合使用，包括状态、类型、
     * 触发入口、日志关键字、时间范围等。支持分页查询和权限控制。</p>
     *
     * @param userId 当前用户ID
     * @param admin 是否为管理员（管理员可查看所有用户的任务）
     * @param status 任务状态过滤（RUNNING/SUCCESS/FAILED/CANCELLED等）
     * @param taskType 任务类型过滤
     * @param triggerEntry 触发入口过滤
     * @param logKeyword 日志关键字过滤（模糊匹配）
     * @param onlyWithLogs 是否仅显示有日志的任务
     * @param startTime 开始时间（按创建时间过滤，格式：yyyy-MM-dd HH:mm:ss）
     * @param endTime 结束时间（按创建时间过滤，格式：yyyy-MM-dd HH:mm:ss）
     * @param page 页码（从1开始，默认1）
     * @param pageSize 每页大小（默认20，最大100）
     * @return 包含records（任务列表）、total（总数）、page（当前页）、pageSize（每页大小）的Map
     */
    public Map<String, Object> traces(Long userId, boolean admin, String status, String taskType, String triggerEntry,
                                       String logKeyword, Boolean onlyWithLogs, String startTime, String endTime,
                                       Integer page, Integer pageSize) {
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT t.trace_id, t.owner_user_id, t.task_type, t.trigger_entry, t.status, t.request_path, t.request_method,
                t.python_trace_count, t.error_message, t.cancellation_reason, t.heartbeat_at, t.started_at, t.finished_at,
                t.finished_reason, t.created_at
            FROM task_trace t
            """);

        // 如果需要过滤日志关键字或仅显示有日志，需要关联日志表
        if ((hasText(logKeyword)) || (onlyWithLogs != null && onlyWithLogs)) {
            sql.append(" LEFT JOIN trace_log l ON t.trace_id = l.trace_id ");
        }

        sql.append(" WHERE 1=1 ");
        ArrayList<Object> args = new ArrayList<>();

        // 非管理员只能查看自己的任务
        if (!admin) { sql.append(" AND t.owner_user_id=?"); args.add(userId); }
        // 状态过滤
        if (hasText(status)) { sql.append(" AND t.status=?"); args.add(status.trim().toUpperCase(Locale.ROOT)); }
        // 任务类型过滤
        if (hasText(taskType)) { sql.append(" AND t.task_type=?"); args.add(taskType.trim()); }
        // 触发入口过滤
        if (hasText(triggerEntry)) { sql.append(" AND t.trigger_entry=?"); args.add(triggerEntry.trim()); }

        // 日志关键字过滤
        if (hasText(logKeyword)) {
            sql.append(" AND l.message LIKE ?");
            args.add("%" + logKeyword.trim() + "%");
        }

        // 仅显示有日志的任务
        if (onlyWithLogs != null && onlyWithLogs) {
            sql.append(" AND EXISTS (SELECT 1 FROM trace_log WHERE trace_id = t.trace_id)");
        }

        // 时间范围过滤（按创建时间）
        if (hasText(startTime)) {
            sql.append(" AND t.created_at >= ?");
            args.add(Timestamp.valueOf(startTime.trim().replace('T', ' ')));
        }
        if (hasText(endTime)) {
            sql.append(" AND t.created_at <= ?");
            args.add(Timestamp.valueOf(endTime.trim().replace('T', ' ')));
        }

        // 先获取总数 - 复制WHERE条件但不包含JOIN部分
        StringBuilder countSqlBuilder = new StringBuilder("SELECT COUNT(DISTINCT t.trace_id) FROM task_trace t");

        // 如果需要过滤日志关键字或仅显示有日志，需要关联日志表
        if ((hasText(logKeyword)) || (onlyWithLogs != null && onlyWithLogs)) {
            countSqlBuilder.append(" LEFT JOIN trace_log l ON t.trace_id = l.trace_id ");
        }

        countSqlBuilder.append(" WHERE 1=1 ");

        // 复制所有WHERE条件到count查询
        if (!admin) countSqlBuilder.append(" AND t.owner_user_id=?");
        if (hasText(status)) countSqlBuilder.append(" AND t.status=?");
        if (hasText(taskType)) countSqlBuilder.append(" AND t.task_type=?");
        if (hasText(triggerEntry)) countSqlBuilder.append(" AND t.trigger_entry=?");
        if (hasText(logKeyword)) countSqlBuilder.append(" AND l.message LIKE ?");
        if (onlyWithLogs != null && onlyWithLogs) countSqlBuilder.append(" AND EXISTS (SELECT 1 FROM trace_log WHERE trace_id = t.trace_id)");
        if (hasText(startTime)) countSqlBuilder.append(" AND t.created_at >= ?");
        if (hasText(endTime)) countSqlBuilder.append(" AND t.created_at <= ?");

        // 执行count查询获取总数
        Integer total = jdbcTemplate.queryForObject(countSqlBuilder.toString(), Integer.class, args.toArray());

        // 添加排序和分页
        sql.append(" ORDER BY t.created_at DESC");

        // 计算分页参数
        int currentPage = page != null && page > 0 ? page : 1;
        int size = pageSize != null && pageSize > 0 ? Math.min(pageSize, 100) : 20;
        int offset = (currentPage - 1) * size;

        sql.append(" LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);

        // 执行查询获取分页数据
        List<Map<String, Object>> records = jdbcTemplate.queryForList(sql.toString(), args.toArray());

        // 构建返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total != null ? total : 0);
        result.put("page", currentPage);
        result.put("pageSize", size);

        return result;
    }

    /** 查询单个任务详情并附带 Python 子任务。 */
    public Map<String, Object> get(String traceId, Long userId, boolean admin) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM task_trace WHERE trace_id=?", traceId);
        if (rows.isEmpty()) throw BusinessException.notFound("任务不存在");
        verifyOwner(rows.get(0), userId, admin);
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("pythonTraces", jdbcTemplate.queryForList("SELECT * FROM task_trace_python WHERE parent_trace_id=? ORDER BY created_at", traceId));
        return result;
    }

    public List<Map<String, Object>> running(Long userId, boolean admin) {
        Map<String, Object> result = traces(userId, admin, null, null, null, null, null, null, null, 1, 500);
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        return records.stream()
                .filter(row -> List.of("RUNNING", "CANCEL_REQUESTED").contains(String.valueOf(row.get("status"))))
                .toList();
    }
    public List<String> taskTypes() { return jdbcTemplate.queryForList("SELECT DISTINCT task_type FROM task_trace WHERE task_type IS NOT NULL ORDER BY task_type", String.class); }
    public List<String> triggerEntries() { return jdbcTemplate.queryForList("SELECT DISTINCT trigger_entry FROM task_trace WHERE trigger_entry IS NOT NULL ORDER BY trigger_entry", String.class); }
    public List<TaskTypeRegistry.Metadata> taskMetadata() { return taskTypeRegistry.all(); }

    /** 查询指定 Trace 的统一 Java/Python 日志。 */
    public List<Map<String, Object>> logs(String traceId, Long userId, boolean admin, String systemType,
                                           String startTime, String endTime, String keyword) {
        get(traceId, userId, admin);

        StringBuilder sql = new StringBuilder("""
            SELECT id, trace_id, python_trace_id, source, level, logger_name, message, thread_name, throwable, logged_at
            FROM trace_log WHERE trace_id=?
            """);

        ArrayList<Object> args = new ArrayList<>();
        args.add(traceId);

        // 系统类型过滤 (java/python)
        if (hasText(systemType)) {
            String normalizedType = systemType.trim().toLowerCase();
            if ("python".equals(normalizedType)) {
                sql.append(" AND python_trace_id IS NOT NULL");
            } else if ("java".equals(normalizedType)) {
                sql.append(" AND python_trace_id IS NULL");
            }
        }

        // 时间范围过滤
        if (hasText(startTime)) {
            sql.append(" AND logged_at >= ?");
            args.add(Timestamp.valueOf(startTime.trim().replace('T', ' ').replace('+', ' ')));
        }
        if (hasText(endTime)) {
            sql.append(" AND logged_at <= ?");
            args.add(Timestamp.valueOf(endTime.trim().replace('T', ' ').replace('+', ' ')));
        }

        // 关键字过滤
        if (hasText(keyword)) {
            sql.append(" AND message LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }

        sql.append(" ORDER BY id");

        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 请求协作取消并传播到全部 Worker 子任务。 */
    public Map<String, Object> cancel(String traceId, Long userId, boolean admin, String reason) {
        Map<String, Object> trace = get(traceId, userId, admin);
        String status = String.valueOf(value(trace, "status"));
        if (!List.of("RUNNING", "CANCEL_REQUESTED").contains(status)) throw new BusinessException("当前任务不可取消");
        jdbcTemplate.update("UPDATE task_trace SET status='CANCEL_REQUESTED', cancellation_reason=?, cancel_requested_at=?, version=version+1 WHERE trace_id=?", truncate(reason, 500), now(), traceId);
        runtimeRegistry.cancel(traceId);
        cancelPythonTraces(traceId, false, userId, reason);
        return get(traceId, userId, admin);
    }

    /** 管理员强制终止父任务及全部 Python 子任务并记录审计。 */
    public Map<String, Object> forceTerminate(String traceId, Long userId, boolean admin, String reason) {
        if (!admin) throw BusinessException.forbidden("仅管理员可强制终止任务");
        get(traceId, userId, true);
        runtimeRegistry.cancel(traceId);
        cancelPythonTraces(traceId, true, userId, reason);
        jdbcTemplate.update("""
            UPDATE task_trace SET status='CANCELLED', finished_reason='FORCE_TERMINATED', force_terminated_by=?,
                force_terminated_at=?, force_terminate_reason=?, cancellation_reason=?, cancel_requested_at=?, finished_at=?, version=version+1
            WHERE trace_id=?
            """, userId, now(), truncate(reason, 500), truncate(reason, 500), now(), now(), traceId);
        runtimeRegistry.remove(traceId);
        return get(traceId, userId, true);
    }

    /** 向 Worker 发出子任务取消请求。 */
    private void cancelPythonTraces(String traceId, boolean force, Long userId, String reason) {
        List<String> ids = jdbcTemplate.queryForList("SELECT python_trace_id FROM task_trace_python WHERE parent_trace_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')", String.class, traceId);
        for (String pythonTraceId : ids) {
            jdbcTemplate.update("UPDATE task_trace_python SET status='CANCEL_REQUESTED', cancel_requested_at=? WHERE python_trace_id=?", now(), pythonTraceId);
            try { workerClient.post().uri("/traces/{id}/cancel", pythonTraceId).retrieve().toBodilessEntity(); } catch (Exception ignored) { }
            if (force) jdbcTemplate.update("""
                UPDATE task_trace_python SET status='CANCELLED', finished_reason='FORCE_TERMINATED', force_terminated_by=?,
                    force_terminated_at=?, force_terminate_reason=?, finished_at=? WHERE python_trace_id=?
                """, userId, now(), truncate(reason, 500), now(), pythonTraceId);
        }
    }

    /** 校验任务归属关系。 */
    private void verifyOwner(Map<String, Object> trace, Long userId, boolean admin) {
        Object rawOwner = value(trace, "owner_user_id");
        if (!admin && (!(rawOwner instanceof Number owner) || owner.longValue() != userId)) throw BusinessException.forbidden("无权访问该任务");
    }
    private Object value(Map<String, Object> row, String name) { return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null); }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String truncate(String value, int length) { String safe = hasText(value) ? value.trim() : "用户请求取消"; return safe.substring(0, Math.min(length, safe.length())); }
    private String truncateNullable(String value, int length) { return value == null ? null : value.substring(0, Math.min(length, value.length())); }
    private Timestamp now() { return Timestamp.from(Instant.now()); }
    private String newTraceId() { return UUID.randomUUID().toString().replace("-", ""); }
}
