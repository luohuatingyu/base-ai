package com.baseai.platform.logging;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 按平台配置定期清理超过保留期限的系统操作审计日志。 */
@Component
public class OperationLogRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(OperationLogRetentionJob.class);
    private final OperationLogRepository repository;
    private final PlatformProperties properties;

    /** 注入操作日志仓储和保留策略。 */
    public OperationLogRetentionJob(OperationLogRepository repository, PlatformProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 每天低峰期删除过期操作日志，负数或零配置回退为安全默认值。 */
    @Scheduled(cron = "${app.audit.operation-log-cleanup-cron:0 35 3 * * *}")
    public void cleanup() {
        cleanup(Instant.now());
    }

    /** 以显式时间执行清理，便于测试精确验证保留边界。 */
    void cleanup(Instant now) {
        int retentionDays = Math.max(1, properties.getAudit().getOperationLogRetentionDays());
        try {
            long removed = repository.deleteByOperatedAtBefore(now.minus(retentionDays, ChronoUnit.DAYS));
            log.info("event=operation_log_cleanup_completed removed={}", removed);
        } catch (RuntimeException exception) {
            log.error("event=operation_log_cleanup_failed", exception);
        }
    }
}
