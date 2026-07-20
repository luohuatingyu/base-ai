package com.baseai.platform.controller;

import com.baseai.platform.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) { this.authService = authService; }

    /** 使用账号密码登录平台。 */
    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    /** 获取当前用户权限快照。 */
    @GetMapping("/me")
    public AuthService.CurrentUser me() { return authService.currentUser(); }

    /** 撤销当前登录令牌。 */
    @PostMapping("/logout")
    public void logout(@RequestHeader("Authorization") String authorization) {
        authService.logout(authorization.substring("Bearer ".length()).trim());
    }

    public record LoginRequest(@NotBlank(message = "请输入账号") String username,
                               @NotBlank(message = "请输入密码") String password) {}
}
