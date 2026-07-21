package com.baseai.platform.web;

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

    /** 为每个 HTTP 请求建立统一 requestId 日志上下文。 */
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
            log.info("event=http_request method={} path={} status={} duration_ms={}",
                request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.remove("requestId");
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
