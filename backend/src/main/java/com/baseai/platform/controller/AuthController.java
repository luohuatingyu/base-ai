package com.baseai.platform.controller;

import com.baseai.platform.service.AuthService;
import com.baseai.platform.security.ClientIpResolver;
import com.baseai.platform.security.SessionCookieService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;
    private final SessionCookieService sessionCookieService;

    public AuthController(AuthService authService, ClientIpResolver clientIpResolver,
                          SessionCookieService sessionCookieService) {
        this.authService = authService;
        this.clientIpResolver = clientIpResolver;
        this.sessionCookieService = sessionCookieService;
    }

    /** 使用账号密码登录平台。 */
    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest,
                                         jakarta.servlet.http.HttpServletResponse servletResponse) {
        AuthService.LoginResult result = authService.login(request.username(), request.password(), new AuthService.LoginMetadata(
            clientIpResolver.resolve(servletRequest), servletRequest.getHeader("User-Agent")));
        sessionCookieService.write(servletResponse, result.token(), result.expiresAt());
        return result;
    }

    /** 获取当前用户权限快照。 */
    @GetMapping("/me")
    public AuthService.CurrentUser me() { return authService.currentUser(); }

    /** 撤销当前登录令牌。 */
    @PostMapping("/logout")
    public void logout(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        authService.logout(sessionCookieService.authenticationToken(request));
        sessionCookieService.clear(response);
    }

    public record LoginRequest(@NotBlank(message = "{auth.username.required}") String username,
                               @NotBlank(message = "{auth.password.required}") String password) {}
}
