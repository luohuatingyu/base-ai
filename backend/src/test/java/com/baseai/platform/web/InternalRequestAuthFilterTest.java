package com.baseai.platform.web;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.InternalRequestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InternalRequestAuthFilterTest {
    private static final String SECRET = "i".repeat(32);
    private static final Instant NOW = Instant.ofEpochSecond(1_788_000_000L);

    /** Spring 必须能够在存在测试构造器时选择生产依赖注入构造器。 */
    @Test
    void createsFilterThroughSpringConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PlatformProperties.class);
            context.registerBean(InternalRequestAuthFilter.class);
            context.refresh();

            assertNotNull(context.getBean(InternalRequestAuthFilter.class));
        }
    }

    /** 有效签名通过后正文仍可由控制器完整读取。 */
    @Test
    void acceptsSignedBodyAndPreservesContent() throws Exception {
        byte[] body = "{\"status\":\"RUNNING\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signed(body, "0123456789abcdef0123456789abcdef");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> received = new AtomicReference<>();
        PlatformProperties properties = new PlatformProperties();
        properties.getResourceLimits().setRequestMaxBytes(1024);

        new RequestSizeLimitFilter(properties).doFilter(request, response, (cached, firstResponse) ->
            new InternalRequestAuthFilter(SECRET, Clock.fixed(NOW, ZoneOffset.UTC)).doFilter(cached, firstResponse,
                (authorized, ignored) -> received.set(new String(authorized.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8))));

        assertEquals(200, response.getStatus());
        assertEquals(new String(body, StandardCharsets.UTF_8), received.get());
    }

    /** 同一 nonce 的第二次请求必须被拒绝。 */
    @Test
    void rejectsReplay() throws Exception {
        InternalRequestAuthFilter filter = new InternalRequestAuthFilter(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String nonce = "fedcba9876543210fedcba9876543210";
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(signed(body, nonce), first, (_request, _response) -> { });
        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilter(signed(body, nonce), replay, (_request, _response) -> { });

        assertEquals(200, first.getStatus());
        assertEquals(401, replay.getStatus());
    }

    /** 伪造内部请求必须在读取正文前被拒绝，避免匿名请求触发正文缓存。 */
    @Test
    void rejectsInvalidSignatureBeforeReadingBody() throws Exception {
        AtomicBoolean read = new AtomicBoolean();
        MockHttpServletRequest source = new MockHttpServletRequest("POST", "/api/internal/events");
        source.setContent("large-untrusted-body".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/events") {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                read.set(true);
                return source.getInputStream();
            }
            @Override public long getContentLengthLong() { return source.getContentLengthLong(); }
            @Override public int getContentLength() { return source.getContentLength(); }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        new InternalRequestAuthFilter(SECRET, Clock.fixed(NOW, ZoneOffset.UTC)).doFilter(request, response,
            (_request, _response) -> { throw new AssertionError("伪造请求不得进入控制器"); });

        assertEquals(401, response.getStatus());
        assertEquals(false, read.get());
    }

    /** 创建固定时间、目标和正文的内部请求。 */
    private MockHttpServletRequest signed(byte[] body, String nonce) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/events");
        request.setContent(body);
        Map<String, String> headers = InternalRequestSigner.headers(SECRET, "POST", "/api/internal/events",
            body, NOW.getEpochSecond(), nonce);
        headers.forEach(request::addHeader);
        return request;
    }
}
