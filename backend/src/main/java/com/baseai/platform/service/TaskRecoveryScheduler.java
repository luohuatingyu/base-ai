package com.baseai.platform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.baseai.platform.trace.TraceRuntimeRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 任务恢复调度器
 * <p>
 * 负责定期检测和恢复失联的任务，防止因进程崩溃、网络中断等异常情况导致任务永久挂起。
 * 主要功能包括：
 * 1. 定期刷新活跃任务的心跳时间
 * 2. 将心跳超时的任务标记为失败状态
 * 3. 应用启动时立即恢复上次异常退出遗留的任务
 * </p>
 *
 * @author baseai
 * @since 1.0
 */
@Component
public class TaskRecoveryScheduler implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryScheduler.class);

    /** MySQL数据库操作模板 */
    private final JdbcTemplate jdbcTemplate;

    /** 心跳超时时间（秒），超过此时间未更新心跳的任务将被标记为失败 */
    private final long timeoutSeconds;

    /** 运行时追踪注册表，维护当前JVM中所有活跃任务的追踪ID */
    private final TraceRuntimeRegistry runtimeRegistry;

    /**
     * 构造函数
     *
     * @param jdbcTemplate MySQL数据库操作模板，用于执行任务状态更新SQL
     * @param timeoutSeconds 心跳超时时间（秒），默认120秒，可通过配置项 app.trace-tracking.heartbeat-timeout-seconds 修改
     * @param runtimeRegistry 运行时追踪注册表，维护当前JVM中所有活跃任务的追踪ID
     */
    public TaskRecoveryScheduler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 @Value("${app.trace-tracking.heartbeat-timeout-seconds:120}") long timeoutSeconds,
                                 TraceRuntimeRegistry runtimeRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeoutSeconds = timeoutSeconds;
        this.runtimeRegistry = runtimeRegistry;
    }

    /**
     * 定期恢复失联的任务
     * <p>
     * 定时执行（每60秒一次）以下操作：
     * 1. 刷新当前JVM中所有活跃任务的心跳时间，证明这些任务仍在正常运行
     * 2. 将心跳超时的Python子任务标记为失败
     * 3. 将心跳超时的Java主任务标记为失败
     * </p>
     * <p>
     * 心跳机制：运行中的任务会定期更新heartbeat_at字段，如果某个任务超过timeoutSeconds时间
     * 未更新心跳，说明该任务所在的进程可能已崩溃或网络中断，需要将其标记为失败状态。
     * </p>
     *
     * @throws RuntimeException 当数据库操作失败时抛出异常，并记录错误日志
     */
    @Scheduled(fixedDelay = 60000)
    public void recoverStaleTraces() {
        try {
            // 获取当前时间戳
            Instant now = Instant.now();

            // 为当前JVM中所有活跃的任务刷新心跳时间
            // 只更新状态为RUNNING或CANCEL_REQUESTED的任务
            int heartbeatUpdates = runtimeRegistry.activeTraceIds().stream().mapToInt(traceId -> jdbcTemplate.update(
                "UPDATE task_trace SET heartbeat_at=?, version=version+1 WHERE trace_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')",
                Timestamp.from(now), traceId)).sum();

            // 计算心跳超时的截止时间点
            Timestamp cutoff = Timestamp.from(now.minusSeconds(timeoutSeconds));

            // 将心跳超时的Python子任务标记为失败
            // 这些任务的heartbeat_at时间早于cutoff，说明已经超时
            int stalePythonTraces = jdbcTemplate.update("""
                UPDATE task_trace_python SET status='FAILED', finished_reason='HEARTBEAT_TIMEOUT', error_message='Worker 子任务心跳超时', finished_at=?
                WHERE status IN ('RUNNING','CANCEL_REQUESTED') AND heartbeat_at < ?
                """, Timestamp.from(now), cutoff);

            // 将心跳超时的Java主任务标记为失败
            int staleJavaTraces = jdbcTemplate.update("""
                UPDATE task_trace SET status='FAILED', finished_reason='HEARTBEAT_TIMEOUT', error_message='任务心跳超时', finished_at=?, version=version+1
                WHERE status IN ('RUNNING','CANCEL_REQUESTED') AND heartbeat_at < ?
                """, Timestamp.from(now), cutoff);

            // 记录心跳刷新日志
            log.debug("event=task_heartbeat_refreshed count={}", heartbeatUpdates);

            // 如果发现失联任务，记录警告日志
            if (stalePythonTraces > 0 || staleJavaTraces > 0) {
                log.warn("event=stale_traces_recovered java_count={} python_count={} timeout_seconds={}",
                    staleJavaTraces, stalePythonTraces, timeoutSeconds);
            }
        } catch (RuntimeException exception) {
            // 记录恢复失败的错误日志，并重新抛出异常
            log.error("event=stale_trace_recovery_failed timeout_seconds={}", timeoutSeconds, exception);
            throw exception;
        }
    }

    /**
     * 应用启动时立即执行任务恢复
     * <p>
     * 实现ApplicationRunner接口，确保在Spring Boot应用启动完成后立即执行一次任务恢复操作。
     * 这样可以恢复上次应用异常退出（如崩溃、强制关闭）时遗留的失联任务。
     * </p>
     *
     * @param arguments 应用启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments arguments) {
        // 应用启动后立即执行一次任务恢复，清理上次异常退出遗留的任务
        recoverStaleTraces();
    }
}
