package com.baseai.platform.aop;

import com.baseai.platform.common.BusinessException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流切面，拦截带有 @RateLimit 注解的方法。
 * 根据限流类型（IP、用户、全局）创建对应的限流器。
 */
@Aspect
@Component
public class RateLimitAspect {
    
    private static final Logger logger = LoggerFactory.getLogger(RateLimitAspect.class);
    
    private final RateLimiterRegistry rateLimiterRegistry;
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    
    public RateLimitAspect() {
        RateLimiterConfig config = RateLimiterConfig.custom()
            .limitForPeriod(10)
            .limitRefreshPeriod(Duration.ofSeconds(60))
            .timeoutDuration(Duration.ofSeconds(5))
            .build();
        this.rateLimiterRegistry = RateLimiterRegistry.of(config);
    }
    
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = generateKey(joinPoint, rateLimit);
        
        RateLimiter limiter = rateLimiters.computeIfAbsent(key, k -> {
            RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(rateLimit.limit())
                .limitRefreshPeriod(Duration.of(rateLimit.period(), rateLimit.timeUnit().toChronoUnit()))
                .timeoutDuration(Duration.ofSeconds(0))
                .build();
            return rateLimiterRegistry.rateLimiter(k, config);
        });
        
        try {
            return limiter.executeSupplier(() -> {
                try {
                    return joinPoint.proceed();
                } catch (Throwable throwable) {
                    if (throwable instanceof RuntimeException) {
                        throw (RuntimeException) throwable;
                    }
                    throw new RuntimeException(throwable);
                }
            });
        } catch (io.github.resilience4j.ratelimiter.RequestNotPermitted e) {
            logger.warn("Rate limit exceeded for key: {}", key);
            throw new BusinessException("rate.limit.exceeded", "请求过于频繁，请稍后再试");
        }
    }
    
    /**
     * 根据限流类型生成限流器key。
     */
    private String generateKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        String methodName = ((MethodSignature) joinPoint.getSignature()).getMethod().getName();
        String limiterName = rateLimit.name().isEmpty() ? methodName : rateLimit.name();
        
        switch (rateLimit.limitType()) {
            case IP:
                return limiterName + ":ip:" + getClientIp();
            case USER:
                return limiterName + ":user:" + getCurrentUserId();
            case GLOBAL:
                return limiterName + ":global";
            default:
                return limiterName;
        }
    }
    
    /**
     * 获取客户端IP地址。
     */
    private String getClientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        
        // 多个代理时取第一个IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        
        return ip != null ? ip : "unknown";
    }
    
    /**
     * 获取当前用户ID。
     */
    private String getCurrentUserId() {
        // TODO: 从安全上下文获取当前用户ID
        // 临时返回IP作为用户标识
        return getClientIp();
    }
}
