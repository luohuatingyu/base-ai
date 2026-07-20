package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    private final UserRepository userRepository;

    public AuthInterceptor(TokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    /** 在控制器执行前完成登录态和 RBAC 权限校验。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        AuthContext.clear();
        TokenClaims claims = tokenService.parseToken(resolveToken(request));
        UserAccount user = userRepository.findAuthenticatedById(claims.userId())
            .orElseThrow(() -> BusinessException.unauthorized("登录用户不存在"));
        if (!Boolean.TRUE.equals(user.getEnabled())) throw BusinessException.forbidden("账号已停用");
        AuthUser authUser = toAuthUser(user);
        AuthContext.set(authUser);
        RequiredPermission required = resolvePermission(handler);
        if (required != null && !authUser.hasPermission(required.value())) throw BusinessException.forbidden("没有操作权限");
        return true;
    }

    /** 请求结束后清理线程级用户上下文。 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        AuthContext.clear();
    }

    /** 从 Authorization 请求头提取 Bearer Token。 */
    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) throw BusinessException.unauthorized("请先登录");
        return authorization.substring(7).trim();
    }

    /** 读取方法或控制器类上的权限声明。 */
    private RequiredPermission resolvePermission(Object handler) {
        if (!(handler instanceof HandlerMethod method)) return null;
        RequiredPermission required = method.getMethodAnnotation(RequiredPermission.class);
        return required != null ? required : method.getBeanType().getAnnotation(RequiredPermission.class);
    }

    /** 从用户角色和菜单构造当前请求权限快照。 */
    private AuthUser toAuthUser(UserAccount user) {
        Set<Role> enabledRoles = user.getRoles().stream().filter(role -> Boolean.TRUE.equals(role.getEnabled())).collect(Collectors.toSet());
        Set<String> roles = enabledRoles.stream().map(Role::getCode).collect(Collectors.toSet());
        Set<String> permissions = enabledRoles.stream().flatMap(role -> role.getMenus().stream())
            .filter(menu -> Boolean.TRUE.equals(menu.getEnabled())).map(Menu::getPermission)
            .filter(permission -> permission != null && !permission.isBlank()).collect(Collectors.toSet());
        return new AuthUser(user.getId(), user.getUsername(), roles, permissions);
    }
}
