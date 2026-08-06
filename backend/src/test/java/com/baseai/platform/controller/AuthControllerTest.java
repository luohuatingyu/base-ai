package com.baseai.platform.controller;

import com.baseai.platform.security.ClientIpResolver;
import com.baseai.platform.security.SessionCookieService;
import com.baseai.platform.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private AuthService authService;
    private ClientIpResolver clientIpResolver;
    private SessionCookieService cookieService;
    private AuthController controller;

    /** 初始化认证控制器依赖。 */
    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        clientIpResolver = mock(ClientIpResolver.class);
        cookieService = mock(SessionCookieService.class);
        controller = new AuthController(authService, clientIpResolver, cookieService);
    }

    /** 登录成功后必须将签发令牌写入安全 Cookie。 */
    @Test
    void loginWritesSessionCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Instant expiresAt = Instant.now().plusSeconds(300);
        AuthService.LoginResult result = new AuthService.LoginResult("jwt-value", expiresAt, null);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authService.login("admin", "password", new AuthService.LoginMetadata("127.0.0.1", null)))
            .thenReturn(result);

        AuthService.LoginResult actual = controller.login(new AuthController.LoginRequest("admin", "password"),
            request, response);

        assertEquals(result, actual);
        verify(cookieService).write(response, "jwt-value", expiresAt);
    }

    /** 登出应撤销当前凭据并清除浏览器 Cookie。 */
    @Test
    void logoutRevokesTokenAndClearsCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookieService.authenticationToken(request)).thenReturn("jwt-value");

        controller.logout(request, response);

        verify(authService).logout("jwt-value");
        verify(cookieService).clear(response);
    }
}
