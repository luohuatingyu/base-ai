package com.baseai.platform.automation;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.job.JobType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class ApiTriggerExecutionService {
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final ApiTriggerService service;
    private final StringRedisTemplate redisTemplate;
    private final int lockSeconds;

    public ApiTriggerExecutionService(ApiTriggerService service, StringRedisTemplate redisTemplate, PlatformProperties properties) {
        this.service = service;
        this.redisTemplate = redisTemplate;
        this.lockSeconds = properties.getApiTrigger().getLockSeconds();
    }

    /** 通过 Redis 锁保证多实例环境同一 Cron 只执行一次。 */
    @JobType(value = "接口定时触发", triggerEntry = "CRON", ownerIdParameter = "ownerUserId", captureRequest = false)
    public void executeScheduled(Long configId, Long ownerUserId) {
        String key = "base-ai:api-trigger:lock:" + configId;
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(lockSeconds)))) return;
        try { service.execute(configId, "CRON"); }
        finally { redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token); }
    }
}
