package com.baseai.platform.service;

import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.domain.SystemSettingSyncOutbox;
import com.baseai.platform.repository.SystemSettingRepository;
import com.baseai.platform.repository.SystemSettingSyncOutboxRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

/** 验证 Outbox 能按数据库当前配置恢复 Redis，并处理删除场景。 */
class SystemSettingSyncOutboxServiceTest {
    /** 未完成的更新任务应读取数据库当前值并同步缓存。 */
    @Test
    void reconcileAppliesCurrentSetting() {
        SystemSettingSyncOutboxRepository outboxRepository = mock(SystemSettingSyncOutboxRepository.class);
        SystemSettingRepository settingRepository = mock(SystemSettingRepository.class);
        SystemSettingCacheService cacheService = mock(SystemSettingCacheService.class);
        SystemSettingSyncOutbox event = event("system.timeout");
        SystemSetting setting = new SystemSetting();
        setting.setConfigKey("system.timeout");
        when(outboxRepository.findTop100ByProcessedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(settingRepository.findByConfigKey("system.timeout")).thenReturn(Optional.of(setting));

        new SystemSettingSyncOutboxService(outboxRepository, settingRepository, cacheService).reconcile();

        verify(cacheService).apply(setting);
        verify(outboxRepository).save(event);
        assertNotNull(event.getProcessedAt());
    }

    /** 数据库已删除配置时，对账任务应删除对应缓存并标记完成。 */
    @Test
    void reconcileDeletesCacheWhenSettingIsMissing() {
        SystemSettingSyncOutboxRepository outboxRepository = mock(SystemSettingSyncOutboxRepository.class);
        SystemSettingRepository settingRepository = mock(SystemSettingRepository.class);
        SystemSettingCacheService cacheService = mock(SystemSettingCacheService.class);
        SystemSettingSyncOutbox event = event("system.timeout");
        when(outboxRepository.findTop100ByProcessedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(settingRepository.findByConfigKey("system.timeout")).thenReturn(Optional.empty());

        new SystemSettingSyncOutboxService(outboxRepository, settingRepository, cacheService).reconcile();

        verify(cacheService).delete("system.timeout");
        verify(outboxRepository).save(event);
        assertNotNull(event.getProcessedAt());
    }

    /** 构造待处理 Outbox 任务。 */
    private SystemSettingSyncOutbox event(String configKey) {
        SystemSettingSyncOutbox event = new SystemSettingSyncOutbox();
        event.setConfigKey(configKey);
        event.setOperation("UPSERT");
        event.setAttempts(0);
        return event;
    }
}
