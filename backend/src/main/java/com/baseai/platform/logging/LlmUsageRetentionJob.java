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

/** 按配置清理超过保留期限的模型使用记录。 */
@Component
public class LlmUsageRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(LlmUsageRetentionJob.class);
    private final JdbcTemplate jdbcTemplate;
    private final PlatformProperties properties;

    /** 注入业务数据库和模型使用保留配置。 */
    public LlmUsageRetentionJob(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate, PlatformProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** 每天低峰期删除过期记录。 */
    @Scheduled(cron = "${app.llm-usage.cleanup-cron:0 50 3 * * *}")
    public void cleanup() { cleanup(Instant.now()); }

    /** 使用显式时间执行清理，便于边界测试。 */
    void cleanup(Instant now) {
        int days = Math.max(1, properties.getLlmUsage().getRetentionDays());
        try {
            int removed = jdbcTemplate.update("DELETE FROM llm_usage_record WHERE started_at < ?",
                Timestamp.from(now.minus(days, ChronoUnit.DAYS)));
            log.info("event=llm_usage_cleanup_completed removed={} retention_days={}", removed, days);
        } catch (RuntimeException exception) {
            log.error("event=llm_usage_cleanup_failed", exception);
        }
    }
}
