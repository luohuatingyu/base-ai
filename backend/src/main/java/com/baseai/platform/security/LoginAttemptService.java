package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@Component
public class LoginAttemptService {
    private final StringRedisTemplate redisTemplate;
    private final String prefix;
    private final int accountIpLimit;
    private final int ipLimit;
    private final Duration window;
    private final Duration blockDuration;

    public LoginAttemptService(StringRedisTemplate redisTemplate, PlatformProperties properties) {
        this.redisTemplate = redisTemplate;
        this.prefix = properties.getPlatform().getCode() + ":login-attempt:";
        this.accountIpLimit = positive(properties.getLoginSecurity().getAccountIpFailures(), 5);
        this.ipLimit = positive(properties.getLoginSecurity().getIpFailures(), 20);
        this.window = Duration.ofMinutes(positive(properties.getLoginSecurity().getWindowMinutes(), 5));
        this.blockDuration = Duration.ofMinutes(positive(properties.getLoginSecurity().getBlockMinutes(), 15));
    }

    /** 在密码校验前拒绝仍处于封禁期的账号来源或客户端地址。 */
    public void checkAllowed(String username, String ipAddress) {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(blockKey("account-ip", accountIp(username, ipAddress))))
                || Boolean.TRUE.equals(redisTemplate.hasKey(blockKey("ip", digest(ipAddress))))) {
                throw new BusinessException(429, "auth.loginRateLimited");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(503, "auth.loginRateLimitUnavailable");
        }
    }

    /** 原子累计失败次数并在达到阈值时建立短期封禁键。 */
    public void recordFailure(String username, String ipAddress) {
        try {
            increment("account-ip", accountIp(username, ipAddress), accountIpLimit);
            increment("ip", digest(ipAddress), ipLimit);
        } catch (RuntimeException exception) {
            throw new BusinessException(503, "auth.loginRateLimitUnavailable");
        }
    }

    /** 登录成功后清除当前账号与来源组合的失败状态，保留 IP 总体防护。 */
    public void clearAccountFailures(String username, String ipAddress) {
        try {
            String identity = accountIp(username, ipAddress);
            redisTemplate.delete(java.util.List.of(countKey("account-ip", identity), blockKey("account-ip", identity)));
        } catch (RuntimeException exception) {
            throw new BusinessException(503, "auth.loginRateLimitUnavailable");
        }
    }

    /** 累计单个窗口计数并在阈值处创建独立封禁状态。 */
    private void increment(String type, String identity, int limit) {
        String countKey = countKey(type, identity);
        Long count = redisTemplate.opsForValue().increment(countKey);
        if (count != null && count == 1L) redisTemplate.expire(countKey, window);
        if (count != null && count >= limit) redisTemplate.opsForValue().set(blockKey(type, identity), "1", blockDuration);
    }

    private String accountIp(String username, String ipAddress) { return digest(username + "\n" + ipAddress); }
    private String countKey(String type, String identity) { return prefix + "count:" + type + ":" + identity; }
    private String blockKey(String type, String identity) { return prefix + "block:" + type + ":" + identity; }
    private int positive(int value, int fallback) { return value > 0 ? value : fallback; }

    /** 对 Redis 标识使用单向摘要，避免缓存键暴露用户名和来源地址。 */
    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("登录限流摘要计算失败", exception);
        }
    }
}
