package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 使用 Redis 对管理员敏感凭据回查执行跨实例失败限流。 */
@Component
public class SecretRevealAttemptService {
    private final StringRedisTemplate redisTemplate;
    private final String prefix;
    private final int failureLimit;
    private final Duration window;
    private final Duration blockDuration;

    /** 注入 Redis，并将非正数配置收敛为安全默认值。 */
    public SecretRevealAttemptService(StringRedisTemplate redisTemplate, PlatformProperties properties) {
        this.redisTemplate = redisTemplate;
        this.prefix = properties.getPlatform().getCode() + ":secret-reveal-attempt:";
        this.failureLimit = positive(properties.getLoginSecurity().getSecretRevealFailures(), 5);
        this.window = Duration.ofMinutes(positive(properties.getLoginSecurity().getSecretRevealWindowMinutes(), 5));
        this.blockDuration = Duration.ofMinutes(positive(properties.getLoginSecurity().getSecretRevealBlockMinutes(), 15));
    }

    /** 在 BCrypt 校验前拒绝仍处于封禁期的管理员账户。 */
    public void checkAllowed(Long userId) {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(blockKey(userId)))) {
                throw new BusinessException(429, "auth.secretRevealRateLimited");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(503, "auth.secretRevealRateLimitUnavailable");
        }
    }

    /** 累计失败并在达到阈值时立即封禁当前管理员账户。 */
    public void recordFailure(Long userId) {
        try {
            String countKey = countKey(userId);
            Long count = redisTemplate.opsForValue().increment(countKey);
            if (count == null) throw new IllegalStateException("Redis did not return a counter");
            if (count == 1L) redisTemplate.expire(countKey, window);
            if (count >= failureLimit) {
                redisTemplate.opsForValue().set(blockKey(userId), "1", blockDuration);
                throw new BusinessException(429, "auth.secretRevealRateLimited");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(503, "auth.secretRevealRateLimitUnavailable");
        }
    }

    /** 验证成功后清除当前管理员的计数和封禁状态。 */
    public void clearFailures(Long userId) {
        try {
            redisTemplate.delete(java.util.List.of(countKey(userId), blockKey(userId)));
        } catch (RuntimeException exception) {
            throw new BusinessException(503, "auth.secretRevealRateLimitUnavailable");
        }
    }

    /** 返回失败计数键。 */
    private String countKey(Long userId) { return prefix + "count:" + userId; }
    /** 返回封禁状态键。 */
    private String blockKey(Long userId) { return prefix + "block:" + userId; }
    /** 仅接受正整数配置。 */
    private int positive(int value, int fallback) { return value > 0 ? value : fallback; }
}
