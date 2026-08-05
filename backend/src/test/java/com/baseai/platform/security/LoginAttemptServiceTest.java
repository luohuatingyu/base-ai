package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginAttemptServiceTest {
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private LoginAttemptService service;

    /** 为账号来源和全局 IP 两级计数准备隔离 Redis 操作。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        PlatformProperties properties = new PlatformProperties();
        properties.getLoginSecurity().setAccountIpFailures(2);
        properties.getLoginSecurity().setIpFailures(2);
        service = new LoginAttemptService(redisTemplate, properties);
    }

    /** 达到失败阈值时账号来源与 IP 两层都必须建立封禁状态。 */
    @Test
    void blocksBothDimensionsAtFailureThreshold() {
        when(values.increment(anyString())).thenReturn(2L);

        service.recordFailure("admin", "198.51.100.7");

        verify(values, times(2)).set(anyString(), eq("1"), eq(Duration.ofMinutes(15)));
    }

    /** 命中任一封禁键时必须返回 429 且不进入密码验证。 */
    @Test
    void rejectsBlockedLogin() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.checkAllowed("admin", "198.51.100.7"));

        assertEquals(429, exception.getStatus());
        assertEquals("auth.loginRateLimited", exception.getMessageKey());
    }

    /** Redis 故障时登录保护必须失败关闭，不能绕过限流。 */
    @Test
    void failsClosedWhenRedisIsUnavailable() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new IllegalStateException("redis unavailable"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.checkAllowed("admin", "198.51.100.7"));

        assertEquals(503, exception.getStatus());
        assertEquals("auth.loginRateLimitUnavailable", exception.getMessageKey());
    }
}
