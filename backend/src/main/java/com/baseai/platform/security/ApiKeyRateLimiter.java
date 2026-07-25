package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.ApiKeyRateLimitType;
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

    /** 按自然时间窗口限制单个 API Key 的请求数量。 */
    public void check(Long apiKeyId, ApiKeyRateLimitType type, Integer limitCount) {
        ApiKeyRateLimitType effectiveType = type == null ? ApiKeyRateLimitType.MINUTE : type;
        if (!effectiveType.isLimited()) return;
        int effectiveLimit = limitCount == null ? 60 : limitCount;
        Duration windowDuration = effectiveType.windowDuration();
        long window = Instant.now().getEpochSecond() / windowDuration.toSeconds();
        String key = cachePrefix + apiKeyId + ":" + effectiveType.name().toLowerCase() + ":" + window;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) redisTemplate.expire(key, windowDuration.multipliedBy(2));
            if (count != null && count > effectiveLimit) {
                throw new BusinessException(429, "RATE_LIMITED", "API Key 请求频率超过限制");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(503, "API_KEY_RATE_LIMIT_UNAVAILABLE", "API Key 限流服务暂不可用");
        }
    }
}
