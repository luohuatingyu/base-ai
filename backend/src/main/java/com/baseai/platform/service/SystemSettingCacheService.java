package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.SystemSetting;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/** 统一管理系统配置运行时缓存，并提供失败补偿所需的旧值快照。 */
@Service
public class SystemSettingCacheService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final ConfigCryptoService cryptoService;
    private final StringRedisTemplate redisTemplate;
    private final String cachePrefix;

    /** 注入缓存、解密服务和平台缓存键前缀。 */
    public SystemSettingCacheService(ConfigCryptoService cryptoService, StringRedisTemplate redisTemplate,
                                     PlatformProperties properties) {
        this.cryptoService = cryptoService;
        this.redisTemplate = redisTemplate;
        this.cachePrefix = properties.getPlatform().getCode() + ":setting:";
    }

    /** 返回配置对应的 Redis 快照，包含值、存在性和剩余有效期。 */
    public CacheSnapshot snapshot(String configKey) {
        String cacheKey = cacheKey(configKey);
        String value = redisTemplate.opsForValue().get(cacheKey);
        Long ttl = redisTemplate.getExpire(cacheKey);
        return new CacheSnapshot(value != null, value, ttl == null ? -2L : ttl);
    }

    /** 将数据库配置同步到运行时缓存，禁用配置不保留缓存值。 */
    public void apply(SystemSetting setting) {
        String cacheKey = cacheKey(setting.getConfigKey());
        if (!Boolean.TRUE.equals(setting.getEnabled())) {
            redisTemplate.delete(cacheKey);
            return;
        }
        String value = Boolean.TRUE.equals(setting.getSensitive())
            ? cryptoService.decrypt(setting.getConfigValue()) : setting.getConfigValue();
        redisTemplate.opsForValue().set(cacheKey, value == null ? "" : value, CACHE_TTL);
    }

    /** 删除指定配置的运行时缓存。 */
    public void delete(String configKey) { redisTemplate.delete(cacheKey(configKey)); }

    /** 将旧缓存快照恢复，用于数据库事务失败时的补偿。 */
    public void restore(String configKey, CacheSnapshot snapshot) {
        String cacheKey = cacheKey(configKey);
        if (!snapshot.present()) {
            redisTemplate.delete(cacheKey);
            return;
        }
        if (snapshot.ttlSeconds() > 0) {
            redisTemplate.opsForValue().set(cacheKey, snapshot.value(), Duration.ofSeconds(snapshot.ttlSeconds()));
        } else {
            redisTemplate.opsForValue().set(cacheKey, snapshot.value());
        }
    }

    /** 启动时批量同步数据库中的系统配置。 */
    public void applyAll(List<SystemSetting> settings) { settings.forEach(this::apply); }

    /** 构建系统配置缓存键。 */
    public String cacheKey(String configKey) { return cachePrefix + configKey; }

    /** Redis 缓存旧值快照。 */
    public record CacheSnapshot(boolean present, String value, long ttlSeconds) {}
}
