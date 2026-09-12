package com.baseai.platform.chat;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实鉴权拦截器验证新会话端点，外部会话与用户存储通过 Mock 隔离。 */
class ChatConversationSecurityTest {
    AuthInterceptor interceptor;
    TokenService tokens;
    UserRepository users;
    AuthUserFactory factory;
    SessionCookieService cookies;
    HandlerMethod handler;

    /** 装配生产鉴权链和真实会话 Controller 元数据。 */
    @BeforeEach void setup() throws Exception {
        tokens = mock(TokenService.class); users = mock(UserRepository.class); factory = mock(AuthUserFactory.class);
        cookies = new SessionCookieService(tokens, new PlatformProperties());
        interceptor = new AuthInterceptor(tokens, users, mock(SessionService.class), mock(ApiKeyAuthenticationService.class), factory, cookies);
        var controller = new ChatConversationController(mock(ChatConversationService.class), mock(ChatStreamClient.class), new ObjectMapper());
        handler = new HandlerMethod(controller, ChatConversationController.class.getMethod("send", Long.class,
            ChatConversationService.SendRequest.class, jakarta.servlet.http.HttpServletResponse.class));
    }

    /** 清理线程身份，避免测试互相影响。 */
    @AfterEach void clear() { AuthContext.clear(); }

    /** 未登录请求必须在进入业务方法之前拒绝。 */
    @Test void rejectsAnonymous() {
        var request = new MockHttpServletRequest("POST", "/api/ai/conversations/1/messages/stream");
        assertEquals(401, assertThrows(BusinessException.class, () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler)).getStatus());
    }

    /** 登录但缺少聊天权限时不能调用流式端点。 */
    @Test void rejectsMissingPermission() {
        var request = authenticated(Set.of());
        assertEquals(403, assertThrows(BusinessException.class, () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler)).getStatus());
    }

    /** 具有权限的登录用户使用既有 Bearer 协议继续通过鉴权。 */
    @Test void acceptsAuthorizedUser() {
        var request = authenticated(Set.of("ai:chat:invoke"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler));
        assertEquals(1L, AuthContext.require().id());
    }

    /** 新流式接口不会被意外公开为 API Key 端点，且不记录提问快照。 */
    @Test void keepsPrivateEndpointAndDisablesContentCapture() {
        assertNull(handler.getMethodAnnotation(ApiKeyEndpoint.class));
        assertFalse(handler.getMethodAnnotation(com.baseai.platform.trace.TraceType.class).captureRequest());
    }

    /** Cookie 写请求缺失匹配的 CSRF 时拒绝。 */
    @Test void rejectsMissingCsrf() {
        var request = authenticated(Set.of("ai:chat:invoke"));
        request.removeHeader("Authorization");
        var response = new MockHttpServletResponse();
        cookies.write(response, "token", Instant.now().plusSeconds(60));
        String name = response.getHeaders("Set-Cookie").stream().filter(value -> value.contains("HttpOnly")).findFirst().orElseThrow().split("=")[0];
        request.setCookies(new jakarta.servlet.http.Cookie(name, "token"));
        assertEquals(403, assertThrows(BusinessException.class, () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler)).getStatus());
    }

    /** 建立外部用户存储与令牌解析的最小测试响应。 */
    private MockHttpServletRequest authenticated(Set<String> permissions) {
        var request = new MockHttpServletRequest("POST", "/api/ai/conversations/1/messages/stream");
        request.addHeader("Authorization", "Bearer token");
        when(tokens.parseToken("token")).thenReturn(new TokenClaims(1L, "user", "token-id", Instant.now().plusSeconds(60)));
        var user = new UserAccount(); user.setId(1L); user.setEnabled(true);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(factory.fromToken(user)).thenReturn(new AuthUser(1L, "user", Set.of(), permissions, AuthenticationType.TOKEN, null, null));
        return request;
    }
}
