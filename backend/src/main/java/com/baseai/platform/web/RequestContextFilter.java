package com.baseai.platform.web;

import com.baseai.platform.trace.TraceTrackingPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component("baseAiRequestContextFilter")
public class RequestContextFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);
    private static final int MAX_REQUEST_ID_LENGTH = 64;
    private static final String REQUEST_LOGGED_ATTRIBUTE = RequestContextFilter.class.getName() + ".requestLogged";
    private final TraceTrackingPolicy trackingPolicy;

    /** 注入统一追踪排除策略。 */
    public RequestContextFilter(TraceTrackingPolicy trackingPolicy) {
        this.trackingPolicy = trackingPolicy;
    }

    /** 为每个受跟踪 API 建立日志上下文，只记录不含业务数据的请求与响应元信息。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String requestId = normalizeRequestId(request.getHeader("X-Request-Id"));
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            try {
                if (!Boolean.TRUE.equals(request.getAttribute(REQUEST_LOGGED_ATTRIBUTE))) logRequest(request);
                logResponse(request, response, durationMs);
            } finally {
                MDC.remove("traceId");
                MDC.remove("requestId");
            }
        }
    }

    /** 仅采集未命中追踪排除配置的后端 API。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/") || trackingPolicy.isConfiguredIgnored(request);
    }

    /** 在业务执行前记录方法和路径，不采集查询参数、请求头或请求正文。 */
    public void logRequest(HttpServletRequest request) {
        safeLog("request", () -> log.info("event=http_request method={} path={}",
            request.getMethod(), request.getRequestURI()));
        request.setAttribute(REQUEST_LOGGED_ATTRIBUTE, Boolean.TRUE);
    }

    /** 在业务执行后记录状态和耗时，不采集响应头或响应正文。 */
    private void logResponse(HttpServletRequest request, HttpServletResponse response, long durationMs) {
        safeLog("response", () -> log.info(
            "event=http_response method={} path={} status={} duration_ms={}",
            request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs));
    }

    /** 隔离日志记录异常，避免请求或响应日志失败影响接口正常返回。 */
    private void safeLog(String stage, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            try {
                log.warn("event=http_log_record_failed stage={} error_type={}", stage, exception.getClass().getSimpleName());
            } catch (RuntimeException ignored) { }
        }
    }

    /** 校验外部请求编号，避免超长或控制字符污染日志。 */
    private String normalizeRequestId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_REQUEST_ID_LENGTH || !value.matches("[A-Za-z0-9._:-]+")) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return value;
    }

}
