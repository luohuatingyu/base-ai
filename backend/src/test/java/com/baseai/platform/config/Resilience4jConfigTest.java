package com.baseai.platform.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resilience4j 配置测试。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class Resilience4jConfigTest {
    
    @Autowired
    private RetryRegistry retryRegistry;
    
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    
    @Autowired
    private Retry pythonWorkerRetry;
    
    @Autowired
    private CircuitBreaker pythonWorkerCircuitBreaker;
    
    @Test
    void shouldConfigureRetryRegistry() {
        assertNotNull(retryRegistry);
        
        // 验证默认配置
        Retry defaultRetry = retryRegistry.retry("default");
        assertNotNull(defaultRetry);
        assertEquals(3, defaultRetry.getRetryConfig().getMaxAttempts());
    }
    
    @Test
    void shouldConfigurePythonWorkerRetry() {
        assertNotNull(pythonWorkerRetry);
        assertEquals("pythonWorker", pythonWorkerRetry.getName());
        assertEquals(3, pythonWorkerRetry.getRetryConfig().getMaxAttempts());
    }
    
    @Test
    void shouldConfigureCircuitBreakerRegistry() {
        assertNotNull(circuitBreakerRegistry);
        
        // 验证默认配置
        CircuitBreaker defaultCircuitBreaker = circuitBreakerRegistry.circuitBreaker("default");
        assertNotNull(defaultCircuitBreaker);
        assertEquals(50, defaultCircuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold());
    }
    
    @Test
    void shouldConfigurePythonWorkerCircuitBreaker() {
        assertNotNull(pythonWorkerCircuitBreaker);
        assertEquals("pythonWorker", pythonWorkerCircuitBreaker.getName());
        assertEquals(50, pythonWorkerCircuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold());
    }
    
    @Test
    void shouldRetryOnFailure() {
        Retry retry = retryRegistry.retry("test-retry");
        
        AtomicInteger attempts = new AtomicInteger(0);
        
        // 模拟失败后成功的场景
        Supplier<String> supplier = Retry.decorateSupplier(retry, () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException("Simulated failure");
            }
            return "success";
        });
        
        String result = supplier.get();
        assertEquals("success", result);
        assertEquals(3, attempts.get());
    }
    
    @Test
    void shouldOpenCircuitBreakerOnHighFailureRate() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("test-circuit-breaker");
        
        // 模拟高失败率
        for (int i = 0; i < 60; i++) {
            try {
                circuitBreaker.executeSupplier(() -> {
                    throw new RuntimeException("Simulated failure");
                });
            } catch (Exception e) {
                // 预期失败
            }
        }
        
        // 熔断器应该打开
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }
}
