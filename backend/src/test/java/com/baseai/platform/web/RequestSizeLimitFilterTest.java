package com.baseai.platform.web;

import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletInputStream;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSizeLimitFilterTest {
    private RequestSizeLimitFilter filter;

    /** 使用较小阈值覆盖正常、边界和超限分支。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getResourceLimits().setRequestMaxBytes(8);
        filter = new RequestSizeLimitFilter(properties);
    }

    /** 正好达到上限的正文应完整交给下游。 */
    @Test
    void acceptsBodyAtExactLimit() throws Exception {
        MockHttpServletRequest request = request("12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (wrapped, output) -> {
            invoked.set(true);
            assertEquals("12345678", new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });

        assertTrue(invoked.get());
    }

    /** 超过上限的正文必须返回 413 且不能进入业务链。 */
    @Test
    void rejectsBodyOverLimit() throws Exception {
        MockHttpServletRequest request = request("123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (wrapped, output) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":413"));
    }

    /** 未声明长度的分块正文也必须按实际读取字节数拒绝。 */
    @Test
    void rejectsChunkedBodyOverLimit() throws Exception {
        MockHttpServletRequest source = request("123456789");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
            @Override public ServletInputStream getInputStream() { return source.getInputStream(); }
        };
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (wrapped, output) -> {
            invoked.set(true);
            wrapped.getInputStream().readAllBytes();
        });

        assertTrue(invoked.get());
        assertEquals(413, response.getStatus());
    }

    /** 未认证的普通请求不应在进入下一过滤器前被整体预读或缓存。 */
    @Test
    void defersReadingBodyUntilDownstreamConsumesIt() throws Exception {
        AtomicBoolean read = new AtomicBoolean();
        MockHttpServletRequest source = request("12345678");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test") {
            @Override public ServletInputStream getInputStream() {
                read.set(true);
                return source.getInputStream();
            }
            @Override public long getContentLengthLong() { return source.getContentLengthLong(); }
            @Override public int getContentLength() { return source.getContentLength(); }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrapped, output) -> {
            assertFalse(read.get());
            assertEquals("12345678", new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });

        assertTrue(read.get());
        assertEquals(200, response.getStatus());
    }

    /** 登录端点必须使用低于通用业务接口的独立正文阈值。 */
    @Test
    void appliesLowerLimitToAnonymousAuthenticationEndpoints() throws Exception {
        PlatformProperties properties = new PlatformProperties();
        properties.getResourceLimits().setRequestMaxBytes(100);
        properties.getResourceLimits().setAuthenticationRequestMaxBytes(4);
        RequestSizeLimitFilter authenticationFilter = new RequestSizeLimitFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        authenticationFilter.doFilter(request, response, (_request, _response) -> {
            throw new AssertionError("超限请求不得进入认证处理器");
        });

        assertEquals(413, response.getStatus());
    }

    /** 创建 JSON POST 请求并填入指定正文。 */
    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
