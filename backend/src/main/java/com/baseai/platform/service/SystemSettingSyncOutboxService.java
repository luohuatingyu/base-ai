package com.baseai.platform.service;

import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.domain.SystemSettingSyncOutbox;
import com.baseai.platform.repository.SystemSettingRepository;
import com.baseai.platform.repository.SystemSettingSyncOutboxRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 记录并执行系统配置缓存同步任务，负责处理异常重启后的对账。 */
@Service
public class SystemSettingSyncOutboxService {
    private final SystemSettingSyncOutboxRepository outboxRepository;
    private final SystemSettingRepository settingRepository;
    private final SystemSettingCacheService cacheService;

    /** 注入 Outbox、配置仓储和缓存同步服务。 */
    public SystemSettingSyncOutboxService(SystemSettingSyncOutboxRepository outboxRepository,
                                          SystemSettingRepository settingRepository,
                                          SystemSettingCacheService cacheService) {
        this.outboxRepository = outboxRepository;
        this.settingRepository = settingRepository;
        this.cacheService = cacheService;
    }

    /** 在配置数据库事务中记录待同步任务。 */
    public SystemSettingSyncOutbox enqueue(String configKey, String operation) {
        SystemSettingSyncOutbox event = new SystemSettingSyncOutbox();
        event.setConfigKey(configKey);
        event.setOperation(operation);
        return outboxRepository.save(event);
    }

    /** 配置和缓存同步完成后标记 Outbox 任务，标记失败时保留任务供下次对账。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(Long id) {
        outboxRepository.findById(id).ifPresent(event -> {
            event.setProcessedAt(Instant.now());
            outboxRepository.save(event);
        });
    }

    /** 定期读取未完成任务并以数据库当前值修复 Redis。 */
    @Scheduled(fixedDelayString = "${SYSTEM_SETTING_OUTBOX_DELAY_MS:30000}")
    @Transactional
    public void reconcile() {
        outboxRepository.findTop100ByProcessedAtIsNullOrderByCreatedAtAsc().forEach(this::reconcileOne);
    }

    /** 对单条任务执行幂等同步，删除操作以数据库不存在为准。 */
    private void reconcileOne(SystemSettingSyncOutbox event) {
        try {
            settingRepository.findByConfigKey(event.getConfigKey())
                .ifPresentOrElse(cacheService::apply, () -> cacheService.delete(event.getConfigKey()));
            event.setAttempts(event.getAttempts() + 1);
            event.setProcessedAt(Instant.now());
            event.setLastError(null);
        } catch (RuntimeException exception) {
            event.setAttempts(event.getAttempts() + 1);
            event.setLastError(trimError(exception));
        }
        outboxRepository.save(event);
    }

    /** 限制错误文本长度，避免同步异常污染 Outbox 表。 */
    private String trimError(RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
