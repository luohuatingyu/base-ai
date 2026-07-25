package com.baseai.platform.service;

import com.baseai.platform.automation.ApiTriggerSecurityConfigurationService;
import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.repository.DictionaryDataRepository;
import com.baseai.platform.repository.DictionaryTypeRepository;
import com.baseai.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;

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

    private SystemConfigurationService service;

    /** 初始化通用系统参数服务，隔离数据库、Redis 和加密依赖。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getPlatform().setCode("baseai");
        service = new SystemConfigurationService(settingRepository, typeRepository, dataRepository,
            cryptoService, redisTemplate, properties);
    }

    /** 通用系统参数列表不得展示接口触发页面专用的安全配置键。 */
    @Test
    void settingsHideApiTriggerSecurityKeys() {
        SystemSetting reserved = setting(ApiTriggerSecurityConfigurationService.ALLOWED_HOSTS_KEY);
        SystemSetting normal = setting("system.timezone");
        when(settingRepository.findAll()).thenReturn(List.of(reserved, normal));

        List<SystemConfigurationService.SettingView> settings = service.settings();

        assertEquals(List.of("system.timezone"), settings.stream().map(SystemConfigurationService.SettingView::configKey).toList());
    }

    /** 通用创建入口不得绕过专用页面写入保留键。 */
    @Test
    void createSettingRejectsApiTriggerSecurityKeys() {
        SystemConfigurationService.SettingCommand command = new SystemConfigurationService.SettingCommand(
            "api-trigger", ApiTriggerSecurityConfigurationService.ALLOW_LOOPBACK_KEY,
            "回环开关", "false", false, true);

        assertThrows(BusinessException.class, () -> service.createSetting(command));
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** 通用更新和删除入口不得修改已经存在的专用安全配置。 */
    @Test
    void updateAndDeleteRejectApiTriggerSecuritySettings() {
        SystemSetting reserved = setting(ApiTriggerSecurityConfigurationService.ALLOWED_HOSTS_KEY);
        when(settingRepository.findById(7L)).thenReturn(Optional.of(reserved));
        SystemConfigurationService.SettingCommand command = new SystemConfigurationService.SettingCommand(
            "api-trigger", "normal.key", "普通参数", "value", false, true);

        assertThrows(BusinessException.class, () -> service.updateSetting(7L, command));
        assertThrows(BusinessException.class, () -> service.deleteSetting(7L));
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(settingRepository, never()).delete(org.mockito.ArgumentMatchers.any());
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
        return setting;
    }
}
