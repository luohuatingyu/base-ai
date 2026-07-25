package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {
    @Mock private TokenService tokenService;
    @Mock private UserRepository userRepository;
    @Mock private SessionService sessionService;
    @Mock private ApiKeyAuthenticationService apiKeyAuthenticationService;
    @Mock private AuthUserFactory authUserFactory;
    private AuthInterceptor interceptor;
    private HandlerMethod handler;

    /** 初始化同时支持 Token 和 API Key 的认证拦截器。 */
    @BeforeEach
    void setUp() throws Exception {
        interceptor = new AuthInterceptor(tokenService, userRepository, sessionService, apiKeyAuthenticationService, authUserFactory);
        handler = new HandlerMethod(new SampleController(), SampleController.class.getDeclaredMethod("invoke"));
    }

    /** 每个测试结束后清理线程级身份。 */
    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 同时提交 Bearer Token 和 API Key 必须拒绝，避免身份歧义。 */
    @Test
    void preHandleRejectsMultipleCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.addHeader("Authorization", "Bearer token");
        request.addHeader("X-API-Key", "bai_live_key.secret");

        assertThrows(BusinessException.class, () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler));
        verify(tokenService, never()).parseToken("token");
        verify(apiKeyAuthenticationService, never()).authenticate(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** API Key 身份应复用 RequiredPermission 权限校验且不创建登录会话。 */
    @Test
    void preHandleAuthenticatesApiKeyWithoutSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.addHeader("X-API-Key", "bai_live_key.secret");
        AuthUser apiKeyUser = new AuthUser(2L, "service", Set.of(), Set.of("test:invoke"),
            AuthenticationType.API_KEY, 9L, "integration");
        when(apiKeyAuthenticationService.authenticate("bai_live_key.secret", request, handler)).thenReturn(apiKeyUser);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler));
        assertEquals(AuthenticationType.API_KEY, AuthContext.require().authenticationType());
        verify(sessionService, never()).touch(org.mockito.ArgumentMatchers.any());
    }

    /** 现有 Bearer Token 认证继续加载用户并刷新会话。 */
    @Test
    void preHandleKeepsBearerTokenBehavior() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.addHeader("Authorization", "Bearer jwt-value");
        TokenClaims claims = new TokenClaims(3L, "user", "token-id", Instant.now().plusSeconds(300));
        UserAccount user = new UserAccount();
        user.setId(3L);
        user.setEnabled(true);
        AuthUser tokenUser = new AuthUser(3L, "user", Set.of(), Set.of("test:invoke"),
            AuthenticationType.TOKEN, null, null);
        when(tokenService.parseToken("jwt-value")).thenReturn(claims);
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        when(authUserFactory.fromToken(user)).thenReturn(tokenUser);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler));
        verify(sessionService).touch(claims);
        assertEquals(AuthenticationType.TOKEN, AuthContext.require().authenticationType());
    }

    private static class SampleController {
        @RequiredPermission("test:invoke")
        public void invoke() {}
    }
}
