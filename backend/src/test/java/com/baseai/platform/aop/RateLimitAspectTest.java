package com.baseai.platform.aop;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Set;

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
    
    /** 每个用例使用独立限流器和已认证身份。 */
    @BeforeEach
    void setUp() {
        aspect = new RateLimitAspect();
        setUser(1L);
        setRequest("192.0.2.1", null);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(getTestMethod());
    }

    /** 清理线程级身份和请求，避免影响其他测试。 */
    @AfterEach
    void tearDown() {
        AuthContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }
    
    /** 额度内请求返回业务结果。 */
    @Test
    void shouldAllowRequestWithinLimit() throws Throwable {
        RateLimit rateLimit = getRateLimitAnnotation(10, 60, RateLimit.LimitType.USER);
        
        when(joinPoint.proceed()).thenReturn("success");
        
        // 第一次请求应该成功
        Object result = aspect.around(joinPoint, rateLimit);
        assertEquals("success", result);
        
        verify(joinPoint, times(1)).proceed();
    }
    
    /** 超额请求保留现有错误协议且不会执行业务。 */
    @Test
    void shouldRejectRequestWhenLimitExceeded() throws Throwable {
        RateLimit rateLimit = getRateLimitAnnotation(1, 60, RateLimit.LimitType.USER);
        
        when(joinPoint.proceed()).thenReturn("success");
        
        // 第一次请求成功
        aspect.around(joinPoint, rateLimit);
        
        // 第二次请求应该被拒绝
        assertLimited(rateLimit);
        verify(joinPoint, times(1)).proceed();
    }
    
    /** 同一出口或无请求上下文时均按用户隔离，切回原用户仍然限流。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldUseDifferentLimitersForDifferentUsers(boolean withoutRequest) throws Throwable {
        if (withoutRequest) RequestContextHolder.resetRequestAttributes();
        RateLimit rateLimit = getRateLimitAnnotation(1, 60, RateLimit.LimitType.USER);
        
        when(joinPoint.proceed()).thenReturn("success");
        
        assertEquals("success", aspect.around(joinPoint, rateLimit));
        setUser(2L);
        assertEquals("success", assertDoesNotThrow(() -> aspect.around(joinPoint, rateLimit)));
        assertLimited(rateLimit);
        setUser(1L);
        assertLimited(rateLimit);
        verify(joinPoint, times(2)).proceed();
    }

    /** 更换地址或伪造代理头不能给同一登录用户增加额度。 */
    @ParameterizedTest
    @ValueSource(strings = {"remote", "X-Forwarded-For", "X-Real-IP"})
    void shouldKeepUserQuotaAcrossAddressChanges(String source) throws Throwable {
        RateLimit rateLimit = getRateLimitAnnotation(1, 60, RateLimit.LimitType.USER);
        when(joinPoint.proceed()).thenReturn("success");
        assertEquals("success", aspect.around(joinPoint, rateLimit));
        setRequest("192.0.2.2", "remote".equals(source) ? null : source);
        assertLimited(rateLimit);
        verify(joinPoint, times(1)).proceed();
    }

    /** 缺失身份或用户 ID 时拒绝调用，不回退到 IP 或共享匿名额度。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectMissingIdentity(boolean missingId) throws Throwable {
        if (missingId) setUser(null);
        else AuthContext.clear();
        RateLimit rateLimit = getRateLimitAnnotation(1, 60, RateLimit.LimitType.USER);
        BusinessException error = assertThrows(BusinessException.class, () -> aspect.around(joinPoint, rateLimit));
        assertEquals(401, error.getStatus());
        assertEquals("auth.required", error.getMessageKey());
        verify(joinPoint, never()).proceed();
        setUser(1L);
        when(joinPoint.proceed()).thenReturn("success");
        assertEquals("success", aspect.around(joinPoint, rateLimit));
    }

    /** IP 策略不要求登录，并继续按地址隔离额度。 */
    @Test
    void shouldPreserveIpStrategy() throws Throwable {
        AuthContext.clear();
        RateLimit rateLimit = getRateLimitAnnotation(1, 60, RateLimit.LimitType.IP);
        when(joinPoint.proceed()).thenReturn("success");
        assertEquals("success", aspect.around(joinPoint, rateLimit));
        setUser(2L);
        assertLimited(rateLimit);
        setRequest("192.0.2.2", null);
        assertEquals("success", aspect.around(joinPoint, rateLimit));
        verify(joinPoint, times(2)).proceed();
    }

    /** 全局策略跨用户和地址共享额度，并兼容匿名入口。 */
    @Test
    void shouldPreserveGlobalStrategy() throws Throwable {
        AuthContext.clear();
        RateLimit rateLimit = getRateLimitAnnotation(1, 60, RateLimit.LimitType.GLOBAL);
        when(joinPoint.proceed()).thenReturn("success");
        assertEquals("success", aspect.around(joinPoint, rateLimit));
        setUser(2L);
        setRequest("192.0.2.2", null);
        assertLimited(rateLimit);
        verify(joinPoint, times(1)).proceed();
    }

    /** 断言超限业务错误，避免误把认证错误当作限流成功。 */
    private void assertLimited(RateLimit rateLimit) {
        BusinessException error = assertThrows(BusinessException.class, () -> aspect.around(joinPoint, rateLimit));
        assertEquals("rate.limit.exceeded", error.getMessageKey());
        assertEquals(400, error.getStatus());
    }

    /** 建立真实认证上下文，用户名相同也必须按 ID 隔离。 */
    private void setUser(Long id) {
        AuthContext.set(new AuthUser(id, "user", Set.of(), Set.of(), AuthenticationType.TOKEN, null, null));
    }

    /** 创建来源地址或代理头发生变化的请求。 */
    private void setRequest(String ip, String header) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(header == null ? ip : "192.0.2.1");
        if (header != null) request.addHeader(header, ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    
    /** 提供切面所需的方法元数据。 */
    private Method getTestMethod() {
        try {
            return this.getClass().getDeclaredMethod("testMethod");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
    
    /** 仅作为限流注解的测试入口。 */
    @RateLimit(name = "test", limit = 10, period = 60, limitType = RateLimit.LimitType.USER)
    public void testMethod() {
    }
    
    /** 为不同策略和边界值提供注解参数。 */
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
