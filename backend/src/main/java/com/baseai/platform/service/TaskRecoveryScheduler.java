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

@Component
public class TaskRecoveryScheduler implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryScheduler.class);
    private final JdbcTemplate jdbcTemplate;
    private final long timeoutSeconds;
    private final TraceRuntimeRegistry runtimeRegistry;

    public TaskRecoveryScheduler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 @Value("${app.trace-tracking.heartbeat-timeout-seconds:120}") long timeoutSeconds,
                                 TraceRuntimeRegistry runtimeRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeoutSeconds = timeoutSeconds;
        this.runtimeRegistry = runtimeRegistry;
    }

    /** 定时将心跳超时的父任务和子任务标记为失联失败。 */
    @Scheduled(fixedDelay = 60000)
    public void recoverStaleTraces() {
        try {
            Instant now = Instant.now();
            int heartbeatUpdates = runtimeRegistry.activeTraceIds().stream().mapToInt(traceId -> jdbcTemplate.update(
                "UPDATE task_trace SET heartbeat_at=?, version=version+1 WHERE trace_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')",
                Timestamp.from(now), traceId)).sum();
            Timestamp cutoff = Timestamp.from(now.minusSeconds(timeoutSeconds));
            int stalePythonTraces = jdbcTemplate.update("""
                UPDATE task_trace_python SET status='FAILED', finished_reason='HEARTBEAT_TIMEOUT', error_message='Worker 子任务心跳超时', finished_at=?
                WHERE status IN ('RUNNING','CANCEL_REQUESTED') AND heartbeat_at < ?
                """, Timestamp.from(now), cutoff);
            int staleJavaTraces = jdbcTemplate.update("""
                UPDATE task_trace SET status='FAILED', finished_reason='HEARTBEAT_TIMEOUT', error_message='任务心跳超时', finished_at=?, version=version+1
                WHERE status IN ('RUNNING','CANCEL_REQUESTED') AND heartbeat_at < ?
                """, Timestamp.from(now), cutoff);
            log.debug("event=task_heartbeat_refreshed count={}", heartbeatUpdates);
            if (stalePythonTraces > 0 || staleJavaTraces > 0) {
                log.warn("event=stale_traces_recovered java_count={} python_count={} timeout_seconds={}",
                    staleJavaTraces, stalePythonTraces, timeoutSeconds);
            }
        } catch (RuntimeException exception) {
            log.error("event=stale_trace_recovery_failed timeout_seconds={}", timeoutSeconds, exception);
            throw exception;
        }
    }

    /** 应用启动后立即恢复上次异常退出遗留的任务。 */
    @Override public void run(ApplicationArguments arguments) { recoverStaleTraces(); }
}
