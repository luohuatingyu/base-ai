package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionCookieServiceTest {
    private TokenService tokenService;
    private SessionCookieService service;

    /** 为每个 Cookie 场景创建隔离的令牌服务。 */
    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        PlatformProperties properties = new PlatformProperties();
        properties.getPlatform().setCode("test-platform");
        service = new SessionCookieService(tokenService, properties);
    }

    /** 登录应写入 HttpOnly 会话 Cookie 和可供双提交校验的 CSRF Cookie。 */
    @Test
    void writesHttpSessionAndCsrfCookiesWithoutSecureAttributeByDefault() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenService.createCsrfToken("jwt-value")).thenReturn("csrf-value");

        service.write(response, "jwt-value", Instant.now().plusSeconds(600));

        java.util.List<String> cookies = response.getHeaders("Set-Cookie");
        String sessionCookie = cookies.stream().filter(value -> value.startsWith("BAI_test-platform_SESSION="))
            .findFirst().orElseThrow();
        String csrfCookie = cookies.stream().filter(value -> value.startsWith("BAI_test-platform_CSRF="))
            .findFirst().orElseThrow();
        assertTrue(sessionCookie.contains("Path=/api"));
        assertTrue(sessionCookie.contains("HttpOnly"));
        assertFalse(sessionCookie.contains("Secure"));
        assertTrue(sessionCookie.contains("SameSite=Strict"));
        assertTrue(csrfCookie.contains("BAI_test-platform_CSRF=csrf-value"));
        assertTrue(csrfCookie.contains("Path=/"));
        assertFalse(csrfCookie.contains("HttpOnly"));
        assertFalse(csrfCookie.contains("Secure"));
    }

    /** 上游提供 HTTPS 时可重新启用 Cookie 的 Secure 属性。 */
    @Test
    void writesSecureCookiesWhenConfigured() {
        PlatformProperties properties = new PlatformProperties();
        properties.getPlatform().setCode("test-platform");
        properties.getSessionCookie().setSecure(true);
        service = new SessionCookieService(tokenService, properties);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenService.createCsrfToken("jwt-value")).thenReturn("csrf-value");

        service.write(response, "jwt-value", Instant.now().plusSeconds(600));

        for (String cookie : response.getHeaders("Set-Cookie")) assertTrue(cookie.contains("Secure"));
    }

    /** Cookie 写请求缺少 CSRF 请求头时必须拒绝。 */
    @Test
    void rejectsMissingCsrfHeader() {
        MockHttpServletRequest request = requestWithCookie("BAI_test-platform_CSRF", "csrf-value");

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.validateCsrf(request, "jwt-value"));

        assertEquals(403, exception.getStatus());
    }

    /** Cookie 与请求头匹配且签名有效时允许继续。 */
    @Test
    void acceptsSignedDoubleSubmitToken() {
        MockHttpServletRequest request = requestWithCookie("BAI_test-platform_CSRF", "csrf-value");
        request.addHeader("X-CSRF-Token", "csrf-value");
        when(tokenService.matchesCsrfToken("jwt-value", "csrf-value")).thenReturn(true);

        service.validateCsrf(request, "jwt-value");
    }

    /** 构造带指定 Cookie 的请求。 */
    private MockHttpServletRequest requestWithCookie(String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setCookies(new jakarta.servlet.http.Cookie(name, value));
        return request;
    }
}
