package com.baseai.platform.web;

import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.trace.TraceRequestSnapshotSanitizer;
import com.baseai.platform.trace.TraceSnapshot;
import com.baseai.platform.trace.TraceTrackingAspect;
import com.baseai.platform.trace.TraceTrackingPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 为未被业务追踪切面接管的 HTTP 请求异步补齐任务调度记录。 */
@Component
public class HttpRequestTraceInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestTraceInterceptor.class);
    private final TraceRequestSnapshotSanitizer sanitizer;
    private final TraceTrackingPolicy trackingPolicy;
    private final HttpRequestTraceAsyncWriter asyncWriter;

    /** 注入请求快照清洗器、排除策略和异步任务写入器。 */
    public HttpRequestTraceInterceptor(TraceRequestSnapshotSanitizer sanitizer,
                                      TraceTrackingPolicy trackingPolicy,
                                      HttpRequestTraceAsyncWriter asyncWriter) {
        this.sanitizer = sanitizer;
        this.trackingPolicy = trackingPolicy;
        this.asyncWriter = asyncWriter;
    }

    /** 请求完成后采集不可变任务数据并异步写入，避免影响接口响应。 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        try {
            if (shouldSkip(request)) return;
            AuthUser authUser = AuthContext.current();
            String traceId = (String) request.getAttribute(TraceIdInterceptor.TRACE_ID_ATTRIBUTE);
            if (authUser == null || traceId == null || traceId.isBlank()
                || Boolean.TRUE.equals(request.getAttribute(TraceTrackingAspect.TRACE_TASK_CREATED_ATTRIBUTE))) return;
            TraceSnapshot snapshot = sanitizer.sanitize(request, new String[0], new Object[0]);
            boolean failed = exception != null || response.getStatus() >= 500;
            String failureMessage = exception == null ? "HTTP status " + response.getStatus() : exception.getMessage();
            asyncWriter.write(traceId, authUser.id(), request.getMethod(), request.getRequestURI(),
                snapshot, failed, failureMessage);
        } catch (RuntimeException loggingException) {
            log.warn("event=http_trace_async_submit_failed error_type={}",
                loggingException.getClass().getSimpleName());
        }
    }

    /** 仅处理未命中排除配置的 API 请求。 */
    private boolean shouldSkip(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/") || trackingPolicy.isConfiguredIgnored(request);
    }
}
