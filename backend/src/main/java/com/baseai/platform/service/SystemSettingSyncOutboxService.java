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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        List<SystemSettingSyncOutbox> pendingEvents = outboxRepository.findTop100ByProcessedAtIsNullOrderByCreatedAtAsc();
        Map<String, List<SystemSettingSyncOutbox>> eventsByKey = new LinkedHashMap<>();
        pendingEvents.forEach(event -> eventsByKey.computeIfAbsent(event.getConfigKey(), ignored -> new java.util.ArrayList<>()).add(event));
        eventsByKey.values().forEach(this::reconcileKey);
    }

    /** 合并同一配置键的任务后执行一次同步，并统一更新任务结果。 */
    private void reconcileKey(List<SystemSettingSyncOutbox> events) {
        String configKey = events.get(0).getConfigKey();
        try {
            settingRepository.findByConfigKey(configKey)
                .ifPresentOrElse(cacheService::apply, () -> cacheService.delete(configKey));
            markSucceeded(events);
        } catch (RuntimeException exception) {
            markFailed(events, trimError(exception));
        }
        outboxRepository.saveAll(events);
    }

    /** 将同一配置键的所有待处理任务标记为成功。 */
    private void markSucceeded(List<SystemSettingSyncOutbox> events) {
        Instant processedAt = Instant.now();
        events.forEach(event -> {
            event.setAttempts(event.getAttempts() + 1);
            event.setProcessedAt(processedAt);
            event.setLastError(null);
        });
    }

    /** 将同一配置键的所有待处理任务保留为可重试失败状态。 */
    private void markFailed(List<SystemSettingSyncOutbox> events, String error) {
        events.forEach(event -> {
            event.setAttempts(event.getAttempts() + 1);
            event.setLastError(error);
        });
    }

    /** 限制错误文本长度，避免同步异常污染 Outbox 表。 */
    private String trimError(RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
