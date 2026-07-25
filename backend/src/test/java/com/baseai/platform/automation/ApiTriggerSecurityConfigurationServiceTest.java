package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiTriggerSecurityConfigurationServiceTest {
    @Mock private SystemSettingRepository settingRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private ApiTriggerSecurityConfigurationService service;

    /** 初始化服务并使用固定平台编码构造可预测的 Redis 键。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getPlatform().setCode("baseai");
        service = new ApiTriggerSecurityConfigurationService(settingRepository, redisTemplate, properties);
    }

    /** 未保存配置时应仅允许全部回环地址并开启私网访问。 */
    @Test
    void currentUsesLoopbackDefaultsWhenSettingsDoNotExist() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(settingRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        ApiTriggerSecurityConfigurationService.ConfigurationView current = service.current();

        assertEquals(List.of("localhost", "127.0.0.1", "::1"), current.allowedHosts());
        assertEquals(true, current.allowPrivateNetwork());
    }

    /** 已保存配置应覆盖默认值，并支持显式空白名单表示拒绝全部 Host。 */
    @Test
    void currentReadsStoredRuntimeValues() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("baseai:api-trigger-security:" + ApiTriggerSecurityConfigurationService.ALLOWED_HOSTS_KEY))
            .thenReturn("");
        when(valueOperations.get("baseai:api-trigger-security:" + ApiTriggerSecurityConfigurationService.ALLOW_PRIVATE_NETWORK_KEY))
            .thenReturn("false");

        ApiTriggerSecurityConfigurationService.ConfigurationView current = service.current();

        assertEquals(List.of(), current.allowedHosts());
        assertEquals(false, current.allowPrivateNetwork());
    }

    /** 保存时应规范化、去重 Host 规则并同时清除两个共享缓存键。 */
    @Test
    void updateNormalizesAndPersistsConfiguration() {
        when(settingRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        ArgumentCaptor<SystemSetting> settingCaptor = ArgumentCaptor.forClass(SystemSetting.class);

        ApiTriggerSecurityConfigurationService.ConfigurationView updated = service.update(
            new ApiTriggerSecurityConfigurationService.UpdateCommand(
                List.of(" LOCALHOST ", "*.Example.com", "localhost", "[::1]", "*"), false));

        assertEquals(List.of("localhost", "*.example.com", "::1", "*"), updated.allowedHosts());
        assertEquals(false, updated.allowPrivateNetwork());
        verify(settingRepository, org.mockito.Mockito.times(2)).save(settingCaptor.capture());
        assertEquals(List.of("localhost,*.example.com,::1,*", "false"),
            settingCaptor.getAllValues().stream().map(SystemSetting::getConfigValue).toList());
        verify(redisTemplate).delete(List.of(
            "baseai:api-trigger-security:" + ApiTriggerSecurityConfigurationService.ALLOWED_HOSTS_KEY,
            "baseai:api-trigger-security:" + ApiTriggerSecurityConfigurationService.ALLOW_PRIVATE_NETWORK_KEY));
    }

    /** 非法 URL、端口或越界 IP 形式不得作为 Host 规则保存。 */
    @Test
    void updateRejectsInvalidHostPatterns() {
        for (String invalid : List.of("http://example.com", "example.com:8080", "*.bad host", "256.1.1.1")) {
            assertThrows(BusinessException.class, () -> service.update(
                new ApiTriggerSecurityConfigurationService.UpdateCommand(List.of(invalid), true)));
        }
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
