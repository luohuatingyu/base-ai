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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecretRevealAttemptServiceTest {
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private SecretRevealAttemptService service;

    /** 使用较低阈值构造可验证封禁边界的 Redis 替身。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        PlatformProperties properties = new PlatformProperties();
        properties.getLoginSecurity().setSecretRevealFailures(2);
        properties.getLoginSecurity().setSecretRevealWindowMinutes(3);
        properties.getLoginSecurity().setSecretRevealBlockMinutes(12);
        service = new SecretRevealAttemptService(redisTemplate, properties);
    }

    /** 首次失败建立有限计数窗口但不提前封禁。 */
    @Test
    void startsFailureWindowOnFirstAttempt() {
        when(values.increment(anyString())).thenReturn(1L);

        service.recordFailure(7L);

        verify(redisTemplate).expire(anyString(), eq(Duration.ofMinutes(3)));
    }

    /** 达到阈值的当前请求立即返回 429 并建立独立封禁键。 */
    @Test
    void blocksAtFailureThreshold() {
        when(values.increment(anyString())).thenReturn(2L);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.recordFailure(7L));

        assertEquals(429, exception.getStatus());
        assertEquals("auth.secretRevealRateLimited", exception.getMessageKey());
        verify(values).set(anyString(), eq("1"), eq(Duration.ofMinutes(12)));
    }

    /** 命中封禁键时必须在密码校验前拒绝。 */
    @Test
    void rejectsBlockedAccount() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.checkAllowed(7L));

        assertEquals(429, exception.getStatus());
        assertEquals("auth.secretRevealRateLimited", exception.getMessageKey());
    }

    /** Redis 故障时敏感凭据回查必须失败关闭。 */
    @Test
    void failsClosedWhenRedisIsUnavailable() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new IllegalStateException("redis unavailable"));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.checkAllowed(7L));

        assertEquals(503, exception.getStatus());
        assertEquals("auth.secretRevealRateLimitUnavailable", exception.getMessageKey());
    }

    /** 验证成功后同时清除计数与封禁状态。 */
    @Test
    void clearsFailureAndBlockKeysAfterSuccess() {
        service.clearFailures(7L);

        verify(redisTemplate).delete(org.mockito.ArgumentMatchers.<java.util.List<String>>argThat(keys ->
            keys.size() == 2 && keys.stream().allMatch(key -> key.contains("secret-reveal-attempt"))));
    }
}
