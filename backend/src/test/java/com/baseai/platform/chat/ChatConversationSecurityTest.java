package com.baseai.platform.chat;

import com.baseai.platform.aop.RateLimitAspect;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
    ChatConversationService conversations;
    ChatStreamClient stream;
    ChatConversationController controller;

    /** 装配生产鉴权链和真实会话 Controller 元数据。 */
    @BeforeEach void setup() throws Exception {
        tokens = mock(TokenService.class); users = mock(UserRepository.class); factory = mock(AuthUserFactory.class);
        cookies = new SessionCookieService(tokens, new PlatformProperties());
        interceptor = new AuthInterceptor(tokens, users, mock(SessionService.class), mock(ApiKeyAuthenticationService.class), factory, cookies);
        conversations = mock(ChatConversationService.class);
        stream = mock(ChatStreamClient.class);
        controller = new ChatConversationController(conversations, stream, new ObjectMapper());
        handler = new HandlerMethod(controller, ChatConversationController.class.getMethod("send", Long.class,
            ChatConversationService.SendRequest.class, jakarta.servlet.http.HttpServletResponse.class));
    }

    /** 清理线程身份，避免测试互相影响。 */
    @AfterEach void clear() {
        AuthContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    /** 真实认证链和聊天 AOP 代理按用户累计十次额度，换 IP 也不能绕过。 */
    @Test void isolatesChatQuotaByAuthenticatedUser() throws Exception {
        var proxyFactory = new AspectJProxyFactory(controller);
        proxyFactory.addAspect(new RateLimitAspect());
        ChatConversationController proxy = proxyFactory.getProxy();
        var mapper = new ObjectMapper();
        var body = new ChatConversationService.SendRequest("request", mapper.valueToTree("hello"), null);
        when(conversations.begin(anyLong(), eq(1L), eq(body))).thenAnswer(invocation ->
            new ChatConversationService.Turn(invocation.getArgument(0), 1L, 10L, List.of(), null));
        doAnswer(invocation -> {
            ChatStreamClient.EventConsumer consumer = invocation.getArgument(1);
            consumer.accept(mapper.valueToTree(Map.of("type", "delta", "content", "answer")));
            consumer.accept(mapper.valueToTree(Map.of("type", "done")));
            return null;
        }).when(stream).stream(any(), any());

        for (long userId : List.of(1L, 2L)) {
            var request = authenticated(userId, Set.of("ai:chat:invoke"));
            request.setRemoteAddr("192.0.2.1");
            for (int count = 0; count < 10; count++) {
                var response = invokeChat(proxy, request, body);
                assertTrue(response.getContentAsString().contains("\"content\":\"answer\""));
                assertTrue(response.getContentAsString().contains("\"type\":\"done\""));
            }
            assertEquals("rate.limit.exceeded", assertThrows(BusinessException.class,
                () -> invokeChat(proxy, request, body)).getMessageKey());
            request.setRemoteAddr("192.0.2.2");
            request.addHeader("X-Forwarded-For", "198.51.100.1");
            assertEquals("rate.limit.exceeded", assertThrows(BusinessException.class,
                () -> invokeChat(proxy, request, body)).getMessageKey());
            verify(conversations, times(10)).begin(userId, 1L, body);
            verify(conversations, times(10)).save(argThat(turn -> turn.owner().equals(userId)),
                eq("answer"), eq("COMPLETED"), any());
        }
        verify(stream, times(20)).stream(any(), any());
    }

    /** 每次请求经过真实鉴权和限流，结束后清理身份及请求上下文。 */
    private MockHttpServletResponse invokeChat(ChatConversationController proxy, MockHttpServletRequest request,
                                              ChatConversationService.SendRequest body) {
        var response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertTrue(interceptor.preHandle(request, response, handler));
            proxy.send(1L, body, response);
            return response;
        } finally {
            interceptor.afterCompletion(request, response, handler, null);
            RequestContextHolder.resetRequestAttributes();
        }
    }

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
        return authenticated(1L, permissions);
    }

    /** 为指定用户建立令牌和用户存储响应，身份解析继续使用真实拦截器。 */
    private MockHttpServletRequest authenticated(Long id, Set<String> permissions) {
        var request = new MockHttpServletRequest("POST", "/api/ai/conversations/1/messages/stream");
        request.addHeader("Authorization", "Bearer token");
        when(tokens.parseToken("token")).thenReturn(new TokenClaims(id, "user", "token-id", Instant.now().plusSeconds(60)));
        var user = new UserAccount(); user.setId(id); user.setEnabled(true);
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(factory.fromToken(user)).thenReturn(new AuthUser(id, "user", Set.of(), permissions, AuthenticationType.TOKEN, null, null));
        return request;
    }
}
