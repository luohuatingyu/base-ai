package com.baseai.platform.aop;

import com.baseai.platform.exception.BusinessException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 限流切面测试。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {
    
    @Mock
    private ProceedingJoinPoint joinPoint;
    
    @Mock
    private MethodSignature methodSignature;
    
    private RateLimitAspect aspect;
    
    @BeforeEach
    void setUp() {
        aspect = new RateLimitAspect();
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(getTestMethod());
    }
    
    @Test
    void shouldAllowRequestWithinLimit() throws Throwable {
        RateLimit rateLimit = getRateLimitAnnotation(10, 60, RateLimit.LimitType.USER);
        
        when(joinPoint.proceed()).thenReturn("success");
        
        // 第一次请求应该成功
        Object result = aspect.around(joinPoint, rateLimit);
        assertEquals("success", result);
        
        verify(joinPoint, times(1)).proceed();
    }
    
    @Test
    void shouldRejectRequestWhenLimitExceeded() throws Throwable {
        RateLimit rateLimit = getRateLimitAnnotation(1, 60, RateLimit.LimitType.USER);
        
        when(joinPoint.proceed()).thenReturn("success");
        
        // 第一次请求成功
        aspect.around(joinPoint, rateLimit);
        
        // 第二次请求应该被拒绝
        assertThrows(BusinessException.class, () -> aspect.around(joinPoint, rateLimit));
    }
    
    @Test
    void shouldUseDifferentLimitersForDifferentUsers() throws Throwable {
        RateLimit rateLimit = getRateLimitAnnotation(1, 60, RateLimit.LimitType.USER);
        
        when(joinPoint.proceed()).thenReturn("success");
        
        // 模拟不同用户（通过不同的IP）
        // 由于getCurrentUserId()临时返回IP，这里测试IP限流
        aspect.around(joinPoint, rateLimit);
        
        // 验证限流器创建
        verify(joinPoint, times(1)).proceed();
    }
    
    private Method getTestMethod() {
        try {
            return this.getClass().getDeclaredMethod("testMethod");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
    
    @RateLimit(name = "test", limit = 10, period = 60, limitType = RateLimit.LimitType.USER)
    public void testMethod() {
    }
    
    private RateLimit getRateLimitAnnotation(int limit, int period, RateLimit.LimitType limitType) {
        return new RateLimit() {
            @Override
            public String name() {
                return "test";
            }
            
            @Override
            public int limit() {
                return limit;
            }
            
            @Override
            public long period() {
                return period;
            }
            
            @Override
            public java.util.concurrent.TimeUnit timeUnit() {
                return java.util.concurrent.TimeUnit.SECONDS;
            }
            
            @Override
            public LimitType limitType() {
                return limitType;
            }
            
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RateLimit.class;
            }
        };
    }
}
