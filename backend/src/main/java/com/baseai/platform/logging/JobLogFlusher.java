package com.baseai.platform.logging;

import com.baseai.platform.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
public class JobLogFlusher {
    private static final Logger log = LoggerFactory.getLogger(JobLogFlusher.class);
    private static final long WARNING_INTERVAL_MS = 30_000;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformProperties properties;
    private long unreportedDropped;
    private long lastDropWarningAt;
    private long lastFlushFailureWarningAt;

    public JobLogFlusher(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate, PlatformProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** 定时批量写入 Java 和 Python 任务日志。 */
    @Scheduled(fixedDelayString = "${app.job-log.flush-interval-ms:500}")
    public void flush() {
        List<JobLogRecord> records = new ArrayList<>(properties.getJobLog().getBatchSize());
        JobLogQueue.drainTo(records, properties.getJobLog().getBatchSize());
        unreportedDropped += JobLogQueue.drainDroppedCount();
        long currentTime = System.currentTimeMillis();
        if (unreportedDropped > 0 && currentTime - lastDropWarningAt >= WARNING_INTERVAL_MS) {
            log.warn("event=job_log_dropped count={}", unreportedDropped);
            unreportedDropped = 0;
            lastDropWarningAt = currentTime;
        }
        if (records.isEmpty()) return;
        try {
            jdbcTemplate.batchUpdate("""
                INSERT INTO task_job_log(job_id, python_job_id, source, level, logger_name, message, thread_name, throwable, logged_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, records, records.size(), (statement, record) -> {
                statement.setString(1, record.jobId());
                statement.setString(2, record.pythonJobId());
                statement.setString(3, record.source());
                statement.setString(4, record.level());
                statement.setString(5, record.loggerName());
                statement.setString(6, record.message());
                statement.setString(7, record.threadName());
                statement.setString(8, record.throwable());
                statement.setTimestamp(9, Timestamp.from(record.loggedAt()));
            });
        } catch (RuntimeException exception) {
            int requeued = JobLogQueue.requeue(records);
            if (currentTime - lastFlushFailureWarningAt >= WARNING_INTERVAL_MS) {
                log.error("event=job_log_flush_failed batch_size={} requeued={}", records.size(), requeued, exception);
                lastFlushFailureWarningAt = currentTime;
            }
        }
    }

    /** 每天清理超过保留周期的任务日志。 */
    @Scheduled(cron = "0 20 3 * * *")
    public void cleanup() {
        try {
            Instant cutoff = Instant.now().minus(properties.getJobLog().getRetentionDays(), ChronoUnit.DAYS);
            jdbcTemplate.update("DELETE FROM task_job_log WHERE logged_at < ?", Timestamp.from(cutoff));
        } catch (RuntimeException exception) {
            log.error("event=job_log_cleanup_failed", exception);
        }
    }
}
