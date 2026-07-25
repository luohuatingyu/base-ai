package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final ApiKeyAuthenticationService apiKeyAuthenticationService;
    private final AuthUserFactory authUserFactory;

    public AuthInterceptor(TokenService tokenService, UserRepository userRepository, SessionService sessionService,
                           ApiKeyAuthenticationService apiKeyAuthenticationService, AuthUserFactory authUserFactory) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.apiKeyAuthenticationService = apiKeyAuthenticationService;
        this.authUserFactory = authUserFactory;
    }

    /** 在控制器执行前完成登录态和 RBAC 权限校验。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        AuthContext.clear();
        AuthUser authUser = resolveAuthUser(request, handler);
        AuthContext.set(authUser);
        RequiredPermission required = resolvePermission(handler);
        if (required != null && !authUser.hasPermission(required.value())) throw BusinessException.forbidden("没有操作权限");
        return true;
    }

    /** 根据请求头选择 Bearer Token 或 API Key 认证。 */
    private AuthUser resolveAuthUser(HttpServletRequest request, Object handler) {
        String authorization = request.getHeader("Authorization");
        String apiKey = request.getHeader("X-API-Key");
        boolean hasToken = authorization != null && !authorization.isBlank();
        boolean hasApiKey = apiKey != null && !apiKey.isBlank();
        if (hasToken && hasApiKey) throw BusinessException.unauthorized("不能同时使用 Token 和 API Key");
        if (hasApiKey) return apiKeyAuthenticationService.authenticate(apiKey.trim(), request, handler);
        TokenClaims claims = tokenService.parseToken(resolveToken(authorization));
        UserAccount user = userRepository.findById(claims.userId())
            .orElseThrow(() -> BusinessException.unauthorized("登录用户不存在"));
        if (!Boolean.TRUE.equals(user.getEnabled())) throw BusinessException.forbidden("账号已停用");
        sessionService.touch(claims);
        return authUserFactory.fromToken(user);
    }

    /** 请求结束后清理线程级用户上下文。 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        AuthContext.clear();
    }

    /** 从 Authorization 请求头提取 Bearer Token。 */
    private String resolveToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw BusinessException.unauthorized("请先登录");
        return authorization.substring(7).trim();
    }

    /** 读取方法或控制器类上的权限声明。 */
    private RequiredPermission resolvePermission(Object handler) {
        if (!(handler instanceof HandlerMethod method)) return null;
        RequiredPermission required = method.getMethodAnnotation(RequiredPermission.class);
        return required != null ? required : method.getBeanType().getAnnotation(RequiredPermission.class);
    }
}
