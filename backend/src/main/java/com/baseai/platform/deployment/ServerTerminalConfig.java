package com.baseai.platform.deployment;

import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthInterceptor;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.TokenService;
import com.baseai.platform.security.SessionCookieService;
import com.baseai.platform.security.AuthUserFactory;
import com.baseai.platform.repository.UserRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;
import java.util.function.Supplier;
import java.net.URI;

/** 注册终端 WebSocket；握手显式复用登录校验并验证外部同源。 */
@Configuration
@EnableWebSocket
public class ServerTerminalConfig implements WebSocketConfigurer {
    private final ServerTerminalService terminals;
    private final AuthInterceptor auth;
    private final TokenService tokens;
    private final SessionCookieService cookies;
    private final UserRepository users;
    private final AuthUserFactory factory;

    /** 注入终端与统一身份校验。 */
    public ServerTerminalConfig(ServerTerminalService terminals, AuthInterceptor auth, TokenService tokens,
        SessionCookieService cookies, UserRepository users, AuthUserFactory factory) {
        this.terminals = terminals; this.auth = auth; this.tokens = tokens;
        this.cookies = cookies; this.users = users; this.factory = factory;
    }

    /** 所有连接必须通过登录、独立权限及同源握手，再消费一次性票据。 */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminals, "/api/servers/terminal/socket").setAllowedOrigins("*").addInterceptors(new HandshakeInterceptor() {
            /** 身份校验不依赖 MVC 控制器拦截器对 WebSocket 的隐式行为。 */
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
                try {
                    if (!(request instanceof ServletServerHttpRequest servletRequest)
                        || !(response instanceof ServletServerHttpResponse servletResponse)
                        || !sameOrigin(request.getHeaders().getOrigin(), request.getHeaders().getFirst("Host"))) {
                        response.setStatusCode(HttpStatus.FORBIDDEN); return false;
                    }
                    auth.preHandle(servletRequest.getServletRequest(), servletResponse.getServletResponse(), null);
                    AuthUser user = AuthContext.require();
                    if (!(user.roles().contains("ADMIN") || user.permissions().contains("operations:server:shell"))) {
                        response.setStatusCode(HttpStatus.FORBIDDEN); return false;
                    }
                    attributes.put("terminalUser", user);
                    String authorization = servletRequest.getServletRequest().getHeader("Authorization");
                    String loginToken = authorization != null && authorization.startsWith("Bearer ")
                        ? authorization.substring(7).trim() : cookies.sessionToken(servletRequest.getServletRequest());
                    attributes.put("terminalIdentity", (Supplier<AuthUser>) () -> currentUser(loginToken));
                    return true;
                } catch (Exception exception) { response.setStatusCode(HttpStatus.FORBIDDEN); return false; }
                finally { AuthContext.clear(); }
            }

            /** 清除握手线程上下文，防止连接间身份残留。 */
            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) { AuthContext.clear(); }
        });
    }

    /** 以原始 Host 校验外部来源，兼容 Caddy TLS 终止且不信任客户端转发头。 */
    static boolean sameOrigin(String origin, String host) {
        if (origin == null || host == null) return false;
        try {
            URI source = URI.create(origin);
            if (!("http".equals(source.getScheme()) || "https".equals(source.getScheme()))
                || source.getHost() == null || source.getRawUserInfo() != null || source.getRawQuery() != null
                || source.getRawFragment() != null || !source.getRawPath().isEmpty()) return false;
            URI target = URI.create(source.getScheme() + "://" + host);
            int defaultPort = "https".equals(source.getScheme()) ? 443 : 80;
            return source.getHost().equalsIgnoreCase(target.getHost()) && target.getRawUserInfo() == null
                && target.getRawPath().isEmpty() && target.getRawQuery() == null && target.getRawFragment() == null
                && (source.getPort() == -1 ? defaultPort : source.getPort()) == (target.getPort() == -1 ? defaultPort : target.getPort());
        } catch (IllegalArgumentException exception) { return false; }
    }

    /** 持续校验令牌撤销、账号停用和权限变更，避免长连接绕过登出。 */
    private AuthUser currentUser(String token) {
        var claims = tokens.parseToken(token);
        var account = users.findById(claims.userId()).orElseThrow();
        if (!Boolean.TRUE.equals(account.getEnabled())) throw new IllegalStateException();
        AuthUser user = factory.fromToken(account);
        if (!(user.roles().contains("ADMIN") || user.permissions().contains("operations:server:shell"))) throw new IllegalStateException();
        return user;
    }
}
