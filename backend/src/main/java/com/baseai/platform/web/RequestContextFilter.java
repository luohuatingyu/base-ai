package com.baseai.platform.web;

import com.baseai.platform.trace.TraceTrackingPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component("baseAiRequestContextFilter")
public class RequestContextFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);
    private static final int MAX_REQUEST_ID_LENGTH = 64;
    private static final String REQUEST_HEADERS_ATTRIBUTE = RequestContextFilter.class.getName() + ".requestHeaders";
    private static final String REQUEST_BODY_ATTRIBUTE = RequestContextFilter.class.getName() + ".requestBody";
    private static final String REQUEST_LOGGED_ATTRIBUTE = RequestContextFilter.class.getName() + ".requestLogged";
    private final ObjectMapper objectMapper;
    private final TraceTrackingPolicy trackingPolicy;

    /** 注入 JSON 序列化器和统一追踪排除策略。 */
    public RequestContextFilter(ObjectMapper objectMapper, TraceTrackingPolicy trackingPolicy) {
        this.objectMapper = objectMapper;
        this.trackingPolicy = trackingPolicy;
    }

    /** 为每个 API 请求建立日志上下文，并完整记录文本入参与出参。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String requestId = normalizeRequestId(request.getHeader("X-Request-Id"));
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        HttpServletRequest requestWrapper = wrapRequest(request);
        prepareRequestLogAttributes(requestWrapper);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        long started = System.nanoTime();
        try {
            chain.doFilter(requestWrapper, responseWrapper);
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            try {
                if (!Boolean.TRUE.equals(requestWrapper.getAttribute(REQUEST_LOGGED_ATTRIBUTE))) logRequest(requestWrapper);
                logResponse(requestWrapper, responseWrapper, durationMs);
            } finally {
                responseWrapper.copyBodyToResponse();
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

    /** 文本请求预先缓存以保证认证失败等提前返回场景仍能记录请求正文。 */
    private HttpServletRequest wrapRequest(HttpServletRequest request) throws IOException {
        if (!shouldRecordBody(request.getContentType(), request.getHeader(HttpHeaders.CONTENT_DISPOSITION))) {
            return request;
        }
        return new CachedBodyRequest(request);
    }

    /** 缓存请求头和可记录文本正文，供 Trace ID 建立后按真实时序输出。 */
    private void prepareRequestLogAttributes(HttpServletRequest request) {
        request.setAttribute(REQUEST_HEADERS_ATTRIBUTE, writeJson(requestHeaders(request)));
        String body = requestBody(request);
        if (body != null && !body.isEmpty()) request.setAttribute(REQUEST_BODY_ATTRIBUTE, body);
    }

    /** 在业务执行前分别记录请求头和非空文本请求体。 */
    public void logRequest(HttpServletRequest request) {
        String headers = (String) request.getAttribute(REQUEST_HEADERS_ATTRIBUTE);
        if (headers == null) headers = writeJson(requestHeaders(request));
        String requestHeaders = headers;
        safeLog("request_headers", () -> log.info(
            "event=http_request_headers method={} path={} query={} request_headers={}",
            request.getMethod(), request.getRequestURI(), valueOrEmpty(request.getQueryString()),
            requestHeaders));
        String body = (String) request.getAttribute(REQUEST_BODY_ATTRIBUTE);
        if (body != null && !body.isEmpty()) {
            safeLog("request_body", () -> log.info(
                "event=http_request_body method={} path={} request_body={}",
                request.getMethod(), request.getRequestURI(), body));
        }
        request.setAttribute(REQUEST_LOGGED_ATTRIBUTE, Boolean.TRUE);
    }

    /** 在业务执行后分别记录响应头和非空文本响应体。 */
    private void logResponse(HttpServletRequest request, ContentCachingResponseWrapper response, long durationMs) {
        safeLog("response_headers", () -> log.info(
            "event=http_response_headers method={} path={} status={} duration_ms={} response_headers={}",
            request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs,
            writeJson(responseHeaders(response))));
        String body = responseBody(response);
        if (body != null && !body.isEmpty()) {
            safeLog("response_body", () -> log.info(
                "event=http_response_body method={} path={} response_body={}",
                request.getMethod(), request.getRequestURI(), body));
        }
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

    /** 按请求头原始顺序收集全部请求头及其多值内容。 */
    private Map<String, List<String>> requestHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        List<String> names = request.getHeaderNames() == null
            ? List.of() : Collections.list(request.getHeaderNames());
        for (String name : names) headers.put(name, Collections.list(request.getHeaders(name)));
        return headers;
    }

    /** 收集 Controller 和响应包装器最终写出的全部响应头。 */
    private Map<String, List<String>> responseHeaders(HttpServletResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) headers.put(name, List.copyOf(response.getHeaders(name)));
        return headers;
    }

    /** 返回完整文本请求正文，空正文、文件和二进制请求不生成正文日志。 */
    private String requestBody(HttpServletRequest request) {
        if (!shouldRecordBody(request.getContentType(), request.getHeader(HttpHeaders.CONTENT_DISPOSITION))) {
            return null;
        }
        if (request instanceof CachedBodyRequest cachedRequest) {
            return decode(cachedRequest.body(), request.getContentType(), request.getCharacterEncoding());
        }
        return "";
    }

    /** 返回完整文本响应正文，空正文、附件和二进制响应不生成正文日志。 */
    private String responseBody(ContentCachingResponseWrapper response) {
        if (!shouldRecordBody(response.getContentType(), response.getHeader(HttpHeaders.CONTENT_DISPOSITION))) {
            return null;
        }
        return decode(response.getContentAsByteArray(), response.getContentType(), response.getCharacterEncoding());
    }

    /** 根据 Content-Type 和 Content-Disposition 判断正文是否属于可记录文本。 */
    private boolean shouldRecordBody(String contentType, String contentDisposition) {
        if (contentDisposition != null && contentDisposition.toLowerCase().contains("attachment")) return false;
        if (contentType == null || contentType.isBlank()) return true;
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            if ("multipart".equalsIgnoreCase(mediaType.getType())) return false;
            if ("text".equalsIgnoreCase(mediaType.getType())) return true;
            String subtype = mediaType.getSubtype().toLowerCase();
            return subtype.equals("json") || subtype.endsWith("+json")
                || subtype.equals("xml") || subtype.endsWith("+xml")
                || subtype.equals("x-www-form-urlencoded")
                || subtype.equals("javascript") || subtype.equals("graphql");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 使用正文声明字符集解码正文，JSON 等未声明 charset 时回退 UTF-8。 */
    private String decode(byte[] body, String contentType, String encoding) {
        if (body.length == 0) return "";
        Charset charset = contentCharset(contentType, encoding);
        return new String(body, charset);
    }

    /** JSON 响应优先按 UTF-8 记录，避免 Servlet 默认 ISO-8859-1 导致中文日志乱码。 */
    private Charset contentCharset(String contentType, String encoding) {
        try {
            if (contentType != null && !contentType.isBlank()) {
                MediaType mediaType = MediaType.parseMediaType(contentType);
                if (isJsonLike(mediaType) && StandardCharsets.ISO_8859_1.equals(mediaType.getCharset())) return StandardCharsets.UTF_8;
                if (mediaType.getCharset() != null) return mediaType.getCharset();
            }
        } catch (IllegalArgumentException ignored) { }
        if (encoding != null && !encoding.isBlank() && !StandardCharsets.ISO_8859_1.name().equalsIgnoreCase(encoding)) {
            try { return Charset.forName(encoding); } catch (IllegalArgumentException ignored) { }
        }
        return StandardCharsets.UTF_8;
    }

    /** 判断媒体类型是否属于 JSON 或 JSON 扩展类型。 */
    private boolean isJsonLike(MediaType mediaType) {
        String subtype = mediaType.getSubtype().toLowerCase();
        return subtype.equals("json") || subtype.endsWith("+json");
    }

    /** 将请求头和响应头序列化为单行 JSON，序列化失败时返回空对象。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn("event=http_log_header_serialization_failed error_type={}", exception.getClass().getSimpleName());
            return "{}";
        }
    }

    /** 将空查询字符串统一输出为空文本。 */
    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 校验外部请求编号，避免超长或控制字符污染日志。 */
    private String normalizeRequestId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_REQUEST_ID_LENGTH || !value.matches("[A-Za-z0-9._:-]+")) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return value;
    }

    /** 缓存文本请求正文，并为后续 Servlet 处理链提供可重复读取的输入流。 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        private byte[] body() {
            return body;
        }

        /** 返回基于缓存正文的新输入流。 */
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

        /** 按请求字符集返回基于缓存正文的读取器。 */
        @Override
        public BufferedReader getReader() {
            Charset charset = StandardCharsets.UTF_8;
            String encoding = getCharacterEncoding();
            if (encoding != null && !encoding.isBlank()) {
                try { charset = Charset.forName(encoding); } catch (IllegalArgumentException ignored) { }
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
