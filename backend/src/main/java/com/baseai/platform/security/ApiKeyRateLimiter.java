package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ApiKeyRateLimiter {
    private final StringRedisTemplate redisTemplate;
    private final String cachePrefix;

    public ApiKeyRateLimiter(StringRedisTemplate redisTemplate, PlatformProperties properties) {
        this.redisTemplate = redisTemplate;
        this.cachePrefix = properties.getPlatform().getCode() + ":api-key-rate:";
    }

    /** 按自然分钟限制单个 API Key 的请求数量。 */
    public void check(Long apiKeyId, int limitPerMinute) {
        long minute = Instant.now().getEpochSecond() / 60;
        String key = cachePrefix + apiKeyId + ":" + minute;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) redisTemplate.expire(key, Duration.ofMinutes(2));
            if (count != null && count > limitPerMinute) {
                throw new BusinessException(429, "RATE_LIMITED", "API Key 请求频率超过限制");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(503, "API_KEY_RATE_LIMIT_UNAVAILABLE", "API Key 限流服务暂不可用");
        }
    }
}
