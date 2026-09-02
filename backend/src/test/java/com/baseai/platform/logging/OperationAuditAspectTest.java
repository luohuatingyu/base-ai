package com.baseai.platform.logging;

import com.baseai.platform.domain.OperationLog;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.security.ClientIpResolver;
import com.baseai.platform.trace.TraceRequestSnapshotSanitizer;
import com.baseai.platform.trace.TraceType;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationAuditAspectTest {
    /** 清理线程请求和认证上下文，避免影响后续测试。 */
    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        AuthContext.clear();
    }

    /** 标记为不采集请求快照的敏感回查接口仍审计操作元数据，但绝不调用序列化器。 */
    @Test
    void omitsRequestDataForExplicitlySensitiveEndpoint() throws Throwable {
        SystemAuditAsyncWriter writer = mock(SystemAuditAsyncWriter.class);
        TraceRequestSnapshotSanitizer sanitizer = mock(TraceRequestSnapshotSanitizer.class);
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        when(ipResolver.resolve(any())).thenReturn("203.0.113.8");
        OperationAuditAspect aspect = new OperationAuditAspect(writer, sanitizer, ipResolver);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/system/api-keys/1/secret");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AuthContext.set(new AuthUser(1L, "admin", Set.of("ADMIN"), Set.of(), AuthenticationType.TOKEN, null, null));
        Method method = SensitiveController.class.getMethod("reveal", String.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringType()).thenReturn(SensitiveController.class);
        when(signature.getParameterNames()).thenReturn(new String[]{"password"});
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        when(point.getSignature()).thenReturn(signature);
        when(point.getArgs()).thenReturn(new Object[]{"never-log-this"});
        when(point.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.audit(point));

        ArgumentCaptor<OperationLog> captured = ArgumentCaptor.forClass(OperationLog.class);
        verify(writer).writeOperation(captured.capture());
        verify(sanitizer, never()).sanitize(any(), any(), any());
        assertNull(captured.getValue().getRequestData());
        assertEquals("/api/system/api-keys/1/secret", captured.getValue().getPath());
    }

    /** 提供具有敏感请求快照声明的最小控制器方法。 */
    @RestController
    static class SensitiveController {
        @TraceType(value = "TEST_SECRET_REVEAL", captureRequest = false)
        public String reveal(String password) {
            return password;
        }
    }
}
