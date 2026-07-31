package com.baseai.platform.web;

import com.baseai.platform.trace.TraceTrackingAspect;
import com.baseai.platform.trace.TraceTrackingPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/** 为非忽略 HTTP 接口建立由后端生成的统一 traceId。 */
@Component
public class TraceIdInterceptor implements HandlerInterceptor {
    public static final String TRACE_ID_ATTRIBUTE = TraceIdInterceptor.class.getName() + ".traceId";
    private final TraceTrackingPolicy trackingPolicy;
    private final RequestContextFilter requestContextFilter;

    public TraceIdInterceptor(TraceTrackingPolicy trackingPolicy, RequestContextFilter requestContextFilter) {
        this.trackingPolicy = trackingPolicy;
        this.requestContextFilter = requestContextFilter;
    }

    /** 在认证和控制器执行前生成 traceId 并写入请求及响应上下文。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (trackingPolicy.isConfiguredIgnored(request)) return true;
        String traceId = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        MDC.put("traceId", traceId);
        response.setHeader(TraceTrackingAspect.TRACE_ID_HEADER, traceId);
        requestContextFilter.logRequest(request);
        return true;
    }
}
