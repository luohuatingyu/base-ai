package com.baseai.platform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.baseai.platform.job.JobRuntimeRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class TaskRecoveryScheduler implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final long timeoutSeconds;
    private final JobRuntimeRegistry runtimeRegistry;

    public TaskRecoveryScheduler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 @Value("${app.job-tracking.heartbeat-timeout-seconds:120}") long timeoutSeconds,
                                 JobRuntimeRegistry runtimeRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeoutSeconds = timeoutSeconds;
        this.runtimeRegistry = runtimeRegistry;
    }

    /** 定时将心跳超时的父任务和子任务标记为失联失败。 */
    @Scheduled(fixedDelay = 60000)
    public void recoverStaleJobs() {
        runtimeRegistry.activeJobIds().forEach(jobId -> jdbcTemplate.update(
            "UPDATE task_job SET heartbeat_at=?, version=version+1 WHERE job_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')",
            Timestamp.from(Instant.now()), jobId));
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(timeoutSeconds));
        jdbcTemplate.update("""
            UPDATE task_job_python SET status='FAILED', finished_reason='HEARTBEAT_TIMEOUT', error_message='Worker 子任务心跳超时', finished_at=?
            WHERE status IN ('RUNNING','CANCEL_REQUESTED') AND heartbeat_at < ?
            """, Timestamp.from(Instant.now()), cutoff);
        jdbcTemplate.update("""
            UPDATE task_job SET status='FAILED', finished_reason='HEARTBEAT_TIMEOUT', error_message='任务心跳超时', finished_at=?, version=version+1
            WHERE status IN ('RUNNING','CANCEL_REQUESTED') AND heartbeat_at < ?
            """, Timestamp.from(Instant.now()), cutoff);
    }

    /** 应用启动后立即恢复上次异常退出遗留的任务。 */
    @Override public void run(ApplicationArguments arguments) { recoverStaleJobs(); }
}
