package com.baseai.platform.service;

import com.baseai.platform.automation.ApiTriggerSecurityConfigurationService;
import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.domain.SystemSettingSyncOutbox;
import com.baseai.platform.repository.DictionaryDataRepository;
import com.baseai.platform.repository.DictionaryTypeRepository;
import com.baseai.platform.repository.SystemSettingRepository;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.workflow.WorkflowAdapterLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemConfigurationServiceTest {
    @Mock private SystemSettingRepository settingRepository;
    @Mock private DictionaryTypeRepository typeRepository;
    @Mock private DictionaryDataRepository dataRepository;
    @Mock private ConfigCryptoService cryptoService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SystemSettingCacheService cacheService;
    @Mock private SystemSettingSyncOutboxService outboxService;

    private SystemConfigurationService service;

    /** 初始化通用系统参数服务，隔离数据库、Redis 和加密依赖。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getPlatform().setCode("baseai");
        service = new SystemConfigurationService(settingRepository, typeRepository, dataRepository,
            cryptoService, redisTemplate, properties, cacheService, outboxService);
    }

    /** 清理线程级登录上下文，避免权限测试污染其他用例。 */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 通用系统参数列表不得展示接口触发页面专用的安全配置键。 */
    @Test
    void settingsHideApiTriggerSecurityKeys() {
        SystemSetting reserved = setting(ApiTriggerSecurityConfigurationService.HOST_RULES_KEY);
        SystemSetting adapter = setting(WorkflowAdapterLifecycleService.N8N_SETTING_KEY);
        SystemSetting normal = setting("system.timezone");
        when(settingRepository.findAll()).thenReturn(List.of(reserved, adapter, normal));

        List<SystemConfigurationService.SettingView> settings = service.settings();

        assertEquals(List.of("system.timezone"), settings.stream().map(SystemConfigurationService.SettingView::configKey).toList());
    }

    /** 通用创建入口不得绕过专用页面写入保留键。 */
    @Test
    void createSettingRejectsApiTriggerSecurityKeys() {
        SystemConfigurationService.SettingCommand command = new SystemConfigurationService.SettingCommand(
            "api-trigger", ApiTriggerSecurityConfigurationService.HOST_RULES_KEY,
            "Host 规则", "[]", false, true);

        assertThrows(BusinessException.class, () -> service.createSetting(command));
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** 通用系统参数入口不得创建节点管理页专用的适配器开关。 */
    @Test
    void createSettingRejectsWorkflowAdapterKeys() {
        SystemConfigurationService.SettingCommand command = new SystemConfigurationService.SettingCommand(
            "workflow-adapter", WorkflowAdapterLifecycleService.N8N_SETTING_KEY,
            "n8n 适配服务", "true", false, true);

        assertThrows(BusinessException.class, () -> service.createSetting(command));
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** 通用更新和删除入口不得修改已经存在的专用安全配置。 */
    @Test
    void updateAndDeleteRejectApiTriggerSecuritySettings() {
        SystemSetting reserved = setting(ApiTriggerSecurityConfigurationService.HOST_RULES_KEY);
        when(settingRepository.findById(7L)).thenReturn(Optional.of(reserved));
        SystemConfigurationService.SettingCommand command = new SystemConfigurationService.SettingCommand(
            "api-trigger", "normal.key", "普通参数", "value", false, true);

        assertThrows(BusinessException.class, () -> service.updateSetting(7L, command));
        assertThrows(BusinessException.class, () -> service.deleteSetting(7L));
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(settingRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    /** 系统参数支持按 Key 模糊过滤、稳定排序和分页边界归一化。 */
    @Test
    void settingsPageFiltersAndNormalizesPagination() {
        SystemSetting first = setting("business.alpha");
        first.setSortOrder(20);
        SystemSetting second = setting("business.beta");
        second.setSortOrder(10);
        when(settingRepository.findAll()).thenReturn(List.of(first, second));

        SystemConfigurationService.SettingPage page = service.settingsPage(0, 0, " BETA ");

        assertEquals(1, page.items().size());
        assertEquals("business.beta", page.items().get(0).configKey());
        assertEquals(1, page.page());
        assertEquals(1, page.size());
        assertEquals(1, page.total());
    }

    /** 系统管理员可以查看敏感参数的实际值，其他用户只能看到脱敏值。 */
    @Test
    void sensitiveValueIsVisibleOnlyToAdmin() {
        SystemSetting sensitive = setting("business.secret");
        sensitive.setSensitive(true);
        sensitive.setConfigValue("encrypted");
        when(settingRepository.findAll()).thenReturn(List.of(sensitive));
        when(cryptoService.decrypt("encrypted")).thenReturn("plain-secret");

        assertEquals("******", service.settings().get(0).configValue());

        AuthContext.set(new AuthUser(1L, "admin", Set.of("ADMIN"), Set.of(), AuthenticationType.TOKEN, null, null));
        assertEquals("plain-secret", service.settings().get(0).configValue());
    }

    /** 系统托管参数只能更新值或启用状态，不能修改元数据或删除。 */
    @Test
    void systemManagedSettingsRejectMetadataChangesAndDeletion() {
        SystemSetting managed = setting("business.managed");
        managed.setSystemManaged(true);
        when(settingRepository.findById(7L)).thenReturn(Optional.of(managed));
        SystemConfigurationService.SettingCommand command = new SystemConfigurationService.SettingCommand(
            "changed", "business.managed", "托管参数", "value", false, true);

        assertThrows(BusinessException.class, () -> service.updateSetting(7L, command));
        assertThrows(BusinessException.class, () -> service.deleteSetting(7L));
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(settingRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    /** 创建配置时应同步运行时缓存并记录待处理 Outbox 任务。 */
    @Test
    void createSettingSynchronizesCacheAndOutbox() {
        SystemConfigurationService.SettingCommand command = new SystemConfigurationService.SettingCommand(
            "system", "system.timeout", "超时时间", "30", false, true);
        SystemSetting saved = setting("system.timeout");
        saved.setConfigValue("30");
        when(settingRepository.save(org.mockito.ArgumentMatchers.any(SystemSetting.class))).thenReturn(saved);
        when(cacheService.snapshot("system.timeout"))
            .thenReturn(new SystemSettingCacheService.CacheSnapshot(false, null, -2));
        when(outboxService.enqueue("system.timeout", "UPSERT")).thenReturn(new SystemSettingSyncOutbox());

        SystemConfigurationService.SettingView view = service.createSetting(command);

        assertEquals("system.timeout", view.configKey());
        verify(cacheService).apply(saved);
        verify(outboxService).enqueue("system.timeout", "UPSERT");
    }

    /** 更新缓存失败时应恢复旧缓存并返回统一业务异常。 */
    @Test
    void updateSettingRestoresCacheWhenSynchronizationFails() {
        SystemSetting existing = setting("system.timeout");
        when(settingRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(settingRepository.save(existing)).thenReturn(existing);
        SystemSettingCacheService.CacheSnapshot previous =
            new SystemSettingCacheService.CacheSnapshot(true, "old", 120);
        when(cacheService.snapshot("system.timeout")).thenReturn(previous);
        when(outboxService.enqueue("system.timeout", "UPSERT")).thenReturn(new SystemSettingSyncOutbox());
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
            .when(cacheService).apply(existing);
        SystemConfigurationService.SettingCommand command = new SystemConfigurationService.SettingCommand(
            "system", "system.timeout", "超时时间", "60", false, true);

        assertThrows(BusinessException.class, () -> service.updateSetting(7L, command));

        verify(cacheService).restore("system.timeout", previous);
    }

    /** 删除缓存失败时应恢复旧缓存，且不得删除数据库记录。 */
    @Test
    void deleteSettingRestoresCacheWhenSynchronizationFails() {
        SystemSetting existing = setting("system.timeout");
        when(settingRepository.findById(7L)).thenReturn(Optional.of(existing));
        SystemSettingCacheService.CacheSnapshot previous =
            new SystemSettingCacheService.CacheSnapshot(true, "old", 120);
        when(cacheService.snapshot("system.timeout")).thenReturn(previous);
        when(outboxService.enqueue("system.timeout", "DELETE")).thenReturn(new SystemSettingSyncOutbox());
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
            .when(cacheService).delete("system.timeout");

        assertThrows(BusinessException.class, () -> service.deleteSetting(7L));

        verify(cacheService).restore("system.timeout", previous);
        verify(settingRepository, never()).delete(existing);
    }

    /** 创建最小可展示系统参数实体。 */
    private SystemSetting setting(String key) {
        SystemSetting setting = new SystemSetting();
        setting.setGroupCode("system");
        setting.setConfigKey(key);
        setting.setName(key);
        setting.setConfigValue("value");
        setting.setSensitive(false);
        setting.setEnabled(true);
        setting.setSortOrder(0);
        setting.setSystemManaged(false);
        return setting;
    }
}
