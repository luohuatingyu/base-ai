package com.baseai.platform.web;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.trace.TraceRequestSnapshotSanitizer;
import com.baseai.platform.trace.TraceSnapshot;
import com.baseai.platform.trace.TraceTrackingPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpRequestTraceInterceptorTest {
    private final TraceRequestSnapshotSanitizer sanitizer = mock(TraceRequestSnapshotSanitizer.class);
    private final HttpRequestTraceAsyncWriter asyncWriter = mock(HttpRequestTraceAsyncWriter.class);
    private final HttpRequestTraceInterceptor interceptor = new HttpRequestTraceInterceptor(
        sanitizer, new TraceTrackingPolicy(new PlatformProperties()), asyncWriter);

    /** 每个测试清理认证上下文。 */
    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 已认证且没有业务任务时应提交异步 HTTP 任务。 */
    @Test
    void submitsAuthenticatedFallbackTaskAsynchronously() {
        String traceId = "trace-http";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setAttribute(TraceIdInterceptor.TRACE_ID_ATTRIBUTE, traceId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        TraceSnapshot snapshot = new TraceSnapshot("{}", "{}");
        AuthContext.set(new AuthUser(7L, "operator", Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));
        when(sanitizer.sanitize(eq(request), any(String[].class), any(Object[].class))).thenReturn(snapshot);

        interceptor.afterCompletion(request, response, new Object(), null);

        verify(asyncWriter).write(traceId, 7L, "POST", "/api/test", snapshot, false, "HTTP status 200");
    }

    /** 已由业务切面创建任务时不得重复提交兜底任务。 */
    @Test
    void skipsWhenBusinessTaskAlreadyExists() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.setAttribute(TraceIdInterceptor.TRACE_ID_ATTRIBUTE, "trace-http");
        request.setAttribute(com.baseai.platform.trace.TraceTrackingAspect.TRACE_TASK_CREATED_ATTRIBUTE, Boolean.TRUE);
        AuthContext.set(new AuthUser(7L, "operator", Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verify(asyncWriter, never()).write(any(), any(), any(), any(), any(), any(Boolean.class), any());
    }

    /** 未认证请求不得提交无归属人的 HTTP 任务。 */
    @Test
    void skipsUnauthenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.setAttribute(TraceIdInterceptor.TRACE_ID_ATTRIBUTE, "trace-http");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verify(asyncWriter, never()).write(any(), any(), any(), any(), any(), any(Boolean.class), any());
    }
}
