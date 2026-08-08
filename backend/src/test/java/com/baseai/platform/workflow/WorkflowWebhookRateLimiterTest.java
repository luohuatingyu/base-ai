package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowWebhookRateLimiterTest {
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> operations;
    private WorkflowWebhookRateLimiter limiter;

    /** 创建每分钟两次的分布式限流器。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getPlatform().setCode("baseai"); properties.getWorkflow().setWebhookRateLimitPerMinute(2);
        limiter = new WorkflowWebhookRateLimiter(redisTemplate, properties);
        lenient().when(redisTemplate.opsForValue()).thenReturn(operations);
    }

    /** 超过组合键限额时返回 429。 */
    @Test
    void rejectsExceededWebhookLimit() {
        when(operations.increment(anyString())).thenReturn(3L);
        assertEquals(429, assertThrows(BusinessException.class,
            () -> limiter.check("ORDERS", "hook", "203.0.113.9")).getStatus());
    }

    /** Redis 不可用时必须安全失败，不能绕过公开入口限流。 */
    @Test
    void failsClosedWhenRedisIsUnavailable() {
        when(operations.increment(anyString())).thenThrow(new IllegalStateException("down"));
        assertEquals(503, assertThrows(BusinessException.class,
            () -> limiter.check("ORDERS", "hook", "203.0.113.9")).getStatus());
    }
}
