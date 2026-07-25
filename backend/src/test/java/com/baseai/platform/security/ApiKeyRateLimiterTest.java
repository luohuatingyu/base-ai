package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyRateLimiterTest {
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    private ApiKeyRateLimiter limiter;

    /** 使用固定平台编码初始化限流缓存前缀。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getPlatform().setCode("baseai");
        limiter = new ApiKeyRateLimiter(redisTemplate, properties);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /** 第一次请求应设置两分钟过期时间并允许通过。 */
    @Test
    void checkInitializesMinuteCounter() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        limiter.check(9L, 10);

        verify(redisTemplate).expire(org.mockito.ArgumentMatchers.startsWith("baseai:api-key-rate:9:"),
            org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(2)));
    }

    /** 超过每分钟限制时返回 429。 */
    @Test
    void checkRejectsExceededLimit() {
        when(valueOperations.increment(anyString())).thenReturn(11L);

        BusinessException exception = assertThrows(BusinessException.class, () -> limiter.check(9L, 10));

        assertEquals(429, exception.getStatus());
    }

    /** Redis 不可用时安全失败并返回 503。 */
    @Test
    void checkFailsClosedWhenRedisUnavailable() {
        when(valueOperations.increment(anyString())).thenThrow(new IllegalStateException("redis down"));

        BusinessException exception = assertThrows(BusinessException.class, () -> limiter.check(9L, 10));

        assertEquals(503, exception.getStatus());
    }
}
