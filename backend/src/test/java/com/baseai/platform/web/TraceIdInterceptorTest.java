package com.baseai.platform.web;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.trace.TraceIgnored;
import com.baseai.platform.trace.TraceTrackingAspect;
import com.baseai.platform.trace.TraceTrackingPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceIdInterceptorTest {
    private final PlatformProperties properties = new PlatformProperties();
    private final TraceIdInterceptor interceptor = new TraceIdInterceptor(new TraceTrackingPolicy(properties));

    /** 每个测试结束后清理 MDC，避免线程上下文污染。 */
    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /** 非忽略接口应由后端生成 traceId 并同步写入请求、日志和响应。 */
    @Test
    void backendGeneratesTraceIdForTrackedRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/system/users");
        request.addHeader(TraceTrackingAspect.TRACE_ID_HEADER, "caller-trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handler("tracked")));

        String traceId = (String) request.getAttribute(TraceIdInterceptor.TRACE_ID_ATTRIBUTE);
        assertEquals(32, traceId.length());
        assertNotEquals("caller-trace-id", traceId);
        assertEquals(traceId, MDC.get("traceId"));
        assertEquals(traceId, response.getHeader(TraceTrackingAspect.TRACE_ID_HEADER));
    }

    /** 配置排除的方法和路径不得生成或返回 traceId。 */
    @Test
    void configuredExclusionsRemainWithoutTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handler("tracked")));

        assertNull(request.getAttribute(TraceIdInterceptor.TRACE_ID_ATTRIBUTE));
        assertNull(response.getHeader(TraceTrackingAspect.TRACE_ID_HEADER));
        assertNull(MDC.get("traceId"));
    }

    /** 控制器注解排除的接口不得生成 traceId。 */
    @Test
    void annotationExclusionRemainsWithoutTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/sample/ignored");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, ignoredHandler()));

        assertNull(request.getAttribute(TraceIdInterceptor.TRACE_ID_ATTRIBUTE));
        assertFalse(response.containsHeader(TraceTrackingAspect.TRACE_ID_HEADER));
    }

    /** 获取普通测试控制器方法的 HandlerMethod。 */
    private HandlerMethod handler(String methodName) throws Exception {
        return new HandlerMethod(new SampleController(), SampleController.class.getDeclaredMethod(methodName));
    }

    /** 获取声明忽略注解的测试控制器方法。 */
    private HandlerMethod ignoredHandler() throws Exception {
        return new HandlerMethod(new IgnoredController(), IgnoredController.class.getDeclaredMethod("ignored"));
    }

    private static class SampleController {
        public void tracked() {}
    }

    @TraceIgnored
    private static class IgnoredController {
        public void ignored() {}
    }
}
