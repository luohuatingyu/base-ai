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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 在请求正文被消费时按端点限制其大小。
 *
 * <p>该过滤器不再为每个匿名请求预读并缓存正文，避免攻击者在认证前强迫 JVM 分配大数组。
 * 对声明了 Content-Length 的请求仍立即拒绝；对分块传输则由受限流在读取到第一个超限字节时中止。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final int maxRequestBytes;
    private final int authenticationRequestMaxBytes;
    private final int webhookRequestMaxBytes;

    /** 从统一配置读取全局、认证和公开 Webhook 的正文上限。 */
    public RequestSizeLimitFilter(PlatformProperties properties) {
        this.maxRequestBytes = positive(properties.getResourceLimits().getRequestMaxBytes(), 20 * 1024 * 1024);
        this.authenticationRequestMaxBytes = bounded(properties.getResourceLimits().getAuthenticationRequestMaxBytes(),
            maxRequestBytes, 64 * 1024);
        this.webhookRequestMaxBytes = bounded(properties.getWorkflow().getWebhookMaxBodyBytes(), maxRequestBytes,
            1024 * 1024);
    }

    /** 在 JSON 反序列化和业务过滤器之前限制请求体，兼容 Content-Length 与分块传输。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        int maximum = maximumFor(request);
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maximum) {
            reject(response);
            return;
        }
        LimitedRequest limited = new LimitedRequest(request, maximum);
        try {
            chain.doFilter(limited, response);
        } catch (ServletException | IOException exception) {
            if (limited.exceeded()) {
                reject(response);
                return;
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (limited.exceeded()) {
                reject(response);
                return;
            }
            throw exception;
        }
        if (limited.exceeded()) reject(response);
    }

    /** 为高风险、公开入口设置低于全局阈值的专用上限。 */
    private int maximumFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/auth/")) return authenticationRequestMaxBytes;
        if (path != null && path.startsWith("/api/workflow-hooks/")) return webhookRequestMaxBytes;
        return maxRequestBytes;
    }

    /** 无请求体语义的方法不包装，避免影响流式响应和普通查询。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean declaredBody = request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
        return !BODY_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT)) && !declaredBody;
    }

    /** 返回稳定的 413 JSON 响应，避免超限内容继续进入日志或控制器。 */
    private void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        response.reset();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"code\":413,\"message\":\"Request body too large\",\"data\":null}");
    }

    /** 限制正整数配置，并避免端点配置意外放大全局上限。 */
    private static int bounded(int value, int upperBound, int fallback) {
        return Math.min(upperBound, positive(value, fallback));
    }

    /** 将非法配置收敛到安全默认值。 */
    private static int positive(int value, int fallback) {
        return Math.min(100 * 1024 * 1024, Math.max(1, value > 0 ? value : fallback));
    }

    /**
     * 按需包装原始输入流。
     *
     * <p>只有下游实际读取正文时才读取字节；当已达到上限后，会再检查一个字节以区分“恰好等于上限”
     * 与“超过上限”。</p>
     */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final int maximum;
        private volatile boolean exceeded;

        private LimitedRequest(HttpServletRequest request, int maximum) {
            super(request);
            this.maximum = maximum;
        }

        /** 返回当前请求是否在下游读取时发现超限。 */
        private boolean exceeded() { return exceeded; }

        /** 为下游创建只计数、不缓存的受限输入流。 */
        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream(), maximum, this);
        }

        /** 按请求声明字符集创建受限读取器。 */
        @Override
        public BufferedReader getReader() throws IOException {
            Charset charset = StandardCharsets.UTF_8;
            if (getCharacterEncoding() != null) {
                try {
                    charset = Charset.forName(getCharacterEncoding());
                } catch (IllegalArgumentException ignored) {
                    // 非法声明按 UTF-8 交给后续 JSON/表单解析器处理。
                }
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        /** 标记超限，供最外层过滤器转换为稳定的 413 响应。 */
        private void markExceeded() { exceeded = true; }
    }

    /** 不缓存正文、但对所有读取方式执行相同字节上限的 Servlet 输入流。 */
    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final int maximum;
        private final LimitedRequest request;
        private int consumed;

        private LimitedInputStream(ServletInputStream delegate, int maximum, LimitedRequest request) {
            this.delegate = delegate;
            this.maximum = maximum;
            this.request = request;
        }

        /** 单字节读取同样在上限后探测一个额外字节。 */
        @Override
        public int read() throws IOException {
            if (!ensureCapacity()) return -1;
            int value = delegate.read();
            if (value >= 0) consumed++;
            return value;
        }

        /** 批量读取最多消费剩余允许字节，下一次读取再检测溢出字节。 */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (!ensureCapacity()) return -1;
            int read = delegate.read(bytes, offset, Math.min(length, maximum - consumed));
            if (read > 0) consumed += read;
            return read;
        }

        /** 通过受限读取实现跳过，防止调用者用 skip 绕过计数。 */
        @Override
        public long skip(long count) throws IOException {
            long skipped = 0;
            byte[] buffer = new byte[(int) Math.min(8192, Math.max(1, count))];
            while (skipped < count) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                if (read < 0) break;
                skipped += read;
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return Math.min(delegate.available(), Math.max(0, maximum - consumed));
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener readListener) { delegate.setReadListener(readListener); }

        /** 在允许字节已耗尽时读取一个探测字节，确认是否真的存在超限内容。 */
        private boolean ensureCapacity() throws IOException {
            if (consumed < maximum) return true;
            if (delegate.read() < 0) return false;
            request.markExceeded();
            throw new RequestBodyTooLargeException();
        }
    }

    /** 仅在过滤器内部传播的读取中止信号。 */
    private static final class RequestBodyTooLargeException extends IOException {
        private RequestBodyTooLargeException() { super("request body exceeds configured limit"); }
    }
}
