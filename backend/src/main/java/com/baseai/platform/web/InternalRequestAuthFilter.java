package com.baseai.platform.web;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.InternalRequestSigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 对所有 Backend 内部端点统一执行 HMAC、时钟窗和 nonce 防重放校验。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InternalRequestAuthFilter extends OncePerRequestFilter {
    private static final long MAXIMUM_SKEW_SECONDS = 60;
    private final String secret;
    private final Clock clock;
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    /** 使用 Python Worker 独立共享密钥创建生产过滤器。 */
    @Autowired
    public InternalRequestAuthFilter(PlatformProperties properties) {
        this(properties.getPythonWorker().getInternalToken(), Clock.systemUTC());
    }

    /** 注入确定时钟以验证过期和重放边界。 */
    InternalRequestAuthFilter(String secret, Clock clock) {
        this.secret = secret == null ? "" : secret;
        this.clock = clock;
    }

    /** 在控制器读取正文前验证签名，并只在签名有效后占用 nonce。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        String target = request.getRequestURI();
        if (request.getQueryString() != null) target += "?" + request.getQueryString();
        String nonce = request.getHeader(InternalRequestSigner.NONCE);
        Instant now = clock.instant();
        boolean valid = InternalRequestSigner.verify(secret, request.getMethod(), target, body,
            request.getHeader(InternalRequestSigner.TIMESTAMP), nonce,
            request.getHeader(InternalRequestSigner.TARGET),
            request.getHeader(InternalRequestSigner.CONTENT_SHA256),
            request.getHeader(InternalRequestSigner.SIGNATURE), now, MAXIMUM_SKEW_SECONDS);
        if (!valid || replayed(nonce, now.getEpochSecond())) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 仅保护明确的内部 API 命名空间。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI() == null || !request.getRequestURI().startsWith("/api/internal/");
    }

    /** 清理过期 nonce 并原子拒绝同一时间窗内的重复请求。 */
    private boolean replayed(String nonce, long now) {
        usedNonces.entrySet().removeIf(entry -> entry.getValue() < now - MAXIMUM_SKEW_SECONDS);
        return nonce == null || usedNonces.putIfAbsent(nonce, now) != null;
    }

    /** 返回固定 401 响应，不暴露签名失败的具体字段。 */
    private void reject(HttpServletResponse response) throws IOException {
        response.reset();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"code\":401,\"message\":\"Unauthorized\",\"data\":null}");
    }
}
