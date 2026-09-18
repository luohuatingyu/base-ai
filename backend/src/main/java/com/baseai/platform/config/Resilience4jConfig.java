package com.baseai.platform.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j 配置类，配置重试和熔断器。
 */
@Configuration
public class Resilience4jConfig {
    
    /**
     * 配置重试注册器。
     */
    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig defaultConfig = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .exponentialBackoffMultiplier(2)
            .retryExceptions(Exception.class)
            .ignoreExceptions(IllegalArgumentException.class, IllegalStateException.class)
            .build();
        
        RetryRegistry registry = RetryRegistry.of(defaultConfig);
        
        // Python Worker 调用重试配置
        RetryConfig pythonWorkerConfig = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofSeconds(1))
            .exponentialBackoffMultiplier(2)
            .retryExceptions(Exception.class)
            .build();
        registry.addConfiguration("pythonWorker", pythonWorkerConfig);
        
        // 外部API调用重试配置
        RetryConfig externalApiConfig = RetryConfig.custom()
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(500))
            .retryExceptions(Exception.class)
            .build();
        registry.addConfiguration("externalApi", externalApiConfig);
        
        return registry;
    }
    
    /**
     * 配置熔断器注册器。
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(10)
            .slidingWindowSize(100)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .build();
        
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultConfig);
        
        // Python Worker 熔断配置
        CircuitBreakerConfig pythonWorkerConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(20))
            .permittedNumberOfCallsInHalfOpenState(5)
            .slidingWindowSize(50)
            .build();
        registry.addConfiguration("pythonWorker", pythonWorkerConfig);
        
        // 外部API熔断配置
        CircuitBreakerConfig externalApiConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .permittedNumberOfCallsInHalfOpenState(3)
            .slidingWindowSize(20)
            .build();
        registry.addConfiguration("externalApi", externalApiConfig);
        
        return registry;
    }
    
    /**
     * Python Worker 重试器。
     */
    @Bean
    public Retry pythonWorkerRetry(RetryRegistry retryRegistry) {
        return retryRegistry.retry("pythonWorker", "pythonWorker");
    }
    
    /**
     * Python Worker 熔断器。
     */
    @Bean
    public CircuitBreaker pythonWorkerCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker("pythonWorker", "pythonWorker");
    }
}
