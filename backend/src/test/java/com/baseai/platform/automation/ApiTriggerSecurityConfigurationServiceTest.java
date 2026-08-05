package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.repository.SystemSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        service = new ApiTriggerSecurityConfigurationService(settingRepository, redisTemplate, properties, new ObjectMapper());
    }

    /** 未保存配置时应没有额外 Host 规则，仅开启回环并关闭其他私网。 */
    @Test
    void currentUsesSafeDefaultsWhenSettingsDoNotExist() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(settingRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        ApiTriggerSecurityConfigurationService.ConfigurationView current = service.current();

        assertEquals(List.of(), current.hostRules());
        assertEquals(true, current.allowLoopback());
        assertEquals(false, current.allowPrivateNetwork());
        assertEquals(false, ApiTriggerSecurityConfigurationService.isReservedKey("api.trigger.allowed-hosts"));
    }

    /** 新版 JSON 规则应被规范化、去重并作为当前运行时配置返回。 */
    @Test
    void currentReadsStructuredRuntimeRules() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(cacheKey(ApiTriggerSecurityConfigurationService.HOST_RULES_KEY)))
            .thenReturn("[{\"type\":\"suffix\",\"value\":\" Factory.AI \"},{\"type\":\"SUFFIX\",\"value\":\"factory.ai\"}]");
        when(valueOperations.get(cacheKey(ApiTriggerSecurityConfigurationService.ALLOW_LOOPBACK_KEY))).thenReturn("false");
        when(valueOperations.get(cacheKey(ApiTriggerSecurityConfigurationService.ALLOW_PRIVATE_NETWORK_KEY))).thenReturn("true");

        ApiTriggerSecurityConfigurationService.ConfigurationView current = service.current();

        assertEquals(List.of(rule("SUFFIX", "factory.ai")), current.hostRules());
        assertEquals(false, current.allowLoopback());
        assertEquals(true, current.allowPrivateNetwork());
    }

    /** 保存时应规范化五类规则、忽略 ANY 值并清除全部共享缓存键。 */
    @Test
    void updateNormalizesAndPersistsStructuredRules() {
        when(settingRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        ArgumentCaptor<SystemSetting> settingCaptor = ArgumentCaptor.forClass(SystemSetting.class);

        ApiTriggerSecurityConfigurationService.ConfigurationView updated = service.update(
            new ApiTriggerSecurityConfigurationService.UpdateCommand(List.of(
                rule(" suffix ", " Factory.AI "), rule("SUFFIX", "factory.ai"),
                rule("contains", "Factory"), rule("ANY", "ignored"), rule("EXACT", "localhost")), false, true));

        assertEquals(List.of(rule("SUFFIX", "factory.ai"), rule("CONTAINS", "factory"), rule("ANY", null)),
            updated.hostRules());
        verify(settingRepository, org.mockito.Mockito.times(3)).save(settingCaptor.capture());
        assertEquals("[{\"type\":\"SUFFIX\",\"value\":\"factory.ai\"},{\"type\":\"CONTAINS\",\"value\":\"factory\"},{\"type\":\"ANY\",\"value\":null}]",
            settingCaptor.getAllValues().get(0).getConfigValue());
        verify(redisTemplate).delete(List.of(cacheKey(ApiTriggerSecurityConfigurationService.HOST_RULES_KEY),
            cacheKey(ApiTriggerSecurityConfigurationService.ALLOW_LOOPBACK_KEY),
            cacheKey(ApiTriggerSecurityConfigurationService.ALLOW_PRIVATE_NETWORK_KEY)));
    }

    /** 非法类型、空值、协议、端口和非法 DNS 字符不得保存。 */
    @Test
    void updateRejectsInvalidStructuredRules() {
        List<ApiTriggerSecurityConfigurationService.HostRule> invalidRules = List.of(
            rule("UNKNOWN", "example.com"), rule("PREFIX", ""), rule("EXACT", "http://example.com"),
            rule("SUFFIX", "example.com:8080"), rule("PREFIX", "bad..host"), rule("CONTAINS", "bad host"));
        for (ApiTriggerSecurityConfigurationService.HostRule invalid : invalidRules) {
            assertThrows(BusinessException.class, () -> service.update(
                new ApiTriggerSecurityConfigurationService.UpdateCommand(List.of(invalid), true, true)));
        }
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** 创建测试规则，减少不同场景的重复构造代码。 */
    private ApiTriggerSecurityConfigurationService.HostRule rule(String type, String value) {
        return new ApiTriggerSecurityConfigurationService.HostRule(type, value);
    }

    /** 构建当前服务使用的 Redis 缓存键。 */
    private String cacheKey(String key) {
        return "baseai:api-trigger-security:" + key;
    }
}
