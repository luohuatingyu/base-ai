package com.baseai.platform.controller;

import com.baseai.platform.service.AuthService;
import com.baseai.platform.security.ClientIpResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService, ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.clientIpResolver = clientIpResolver;
    }

    /** 使用账号密码登录平台。 */
    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return authService.login(request.username(), request.password(), new AuthService.LoginMetadata(
            clientIpResolver.resolve(servletRequest), servletRequest.getHeader("User-Agent")));
    }

    /** 获取当前用户权限快照。 */
    @GetMapping("/me")
    public AuthService.CurrentUser me() { return authService.currentUser(); }

    /** 撤销当前登录令牌。 */
    @PostMapping("/logout")
    public void logout(@RequestHeader("Authorization") String authorization) {
        authService.logout(authorization.substring("Bearer ".length()).trim());
    }

    public record LoginRequest(@NotBlank(message = "{auth.username.required}") String username,
                               @NotBlank(message = "{auth.password.required}") String password) {}
}
