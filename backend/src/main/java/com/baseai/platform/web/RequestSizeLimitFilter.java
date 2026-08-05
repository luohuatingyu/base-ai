package com.baseai.platform.web;

import com.baseai.platform.config.PlatformProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final int maxRequestBytes;

    public RequestSizeLimitFilter(PlatformProperties properties) {
        this.maxRequestBytes = Math.min(100 * 1024 * 1024,
            Math.max(1, properties.getResourceLimits().getRequestMaxBytes()));
    }

    /** 在 JSON 反序列化和业务过滤器之前限制请求体，兼容 Content-Length 与分块传输。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxRequestBytes) {
            reject(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxRequestBytes + 1);
        if (body.length > maxRequestBytes) {
            reject(response);
            return;
        }
        chain.doFilter(new CachedRequest(request, body), response);
    }

    /** 无请求体语义的方法不做预读取，避免影响流式响应和普通查询。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean declaredBody = request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
        return !BODY_METHODS.contains(request.getMethod().toUpperCase(java.util.Locale.ROOT)) && !declaredBody;
    }

    /** 返回稳定的 413 JSON 响应，避免超限内容继续进入日志或控制器。 */
    private void reject(HttpServletResponse response) throws IOException {
        response.reset();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"code\":413,\"message\":\"Request body too large\",\"data\":null}");
    }

    /** 基于已校验字节缓存提供可重复读取的 Servlet 请求。 */
    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }

        /** 为下游组件创建独立的缓存输入流。 */
        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) { }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
            };
        }

        /** 按请求声明字符集返回缓存读取器。 */
        @Override
        public BufferedReader getReader() {
            Charset charset = StandardCharsets.UTF_8;
            if (getCharacterEncoding() != null) {
                try { charset = Charset.forName(getCharacterEncoding()); } catch (IllegalArgumentException ignored) { }
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
