package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** 使用 Redis 自然分钟窗口限制公开 Webhook，Redis 故障时安全失败。 */
@Component
public class WorkflowWebhookRateLimiter {
    private final StringRedisTemplate redisTemplate;
    private final String cachePrefix;
    private final int limit;

    /** 注入 Redis、平台命名空间和安全限额。 */
    public WorkflowWebhookRateLimiter(StringRedisTemplate redisTemplate, PlatformProperties properties) {
        this.redisTemplate = redisTemplate;
        this.cachePrefix = properties.getPlatform().getCode() + ":workflow-hook-rate:";
        this.limit = Math.max(1, properties.getWorkflow().getWebhookRateLimitPerMinute());
    }

    /** 按工作流、节点和可信客户端 IP 组合限制每分钟请求数量。 */
    public void check(String workflowCode, String nodeId, String clientIp) {
        long window = Instant.now().getEpochSecond() / 60;
        String key = cachePrefix + workflowCode + ":" + nodeId + ":" + clientIp + ":" + window;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) redisTemplate.expire(key, Duration.ofMinutes(2));
            if (count != null && count > limit) throw new BusinessException(429, "workflow.webhookRateLimitExceeded");
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException(503, "workflow.webhookRateLimitUnavailable"); }
    }
}
