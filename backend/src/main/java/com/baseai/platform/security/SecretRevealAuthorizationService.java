package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** 对敏感凭据回查执行管理员二次密码验证。 */
@Component
public class SecretRevealAuthorizationService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 注入当前管理员账户查询和 BCrypt 密码校验器。 */
    public SecretRevealAuthorizationService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 要求当前系统管理员重新输入自身密码后才能读取可逆保存的敏感凭据。
     *
     * <p>接口调用者已通过会话认证；此处额外校验密码，以降低被劫持会话直接导出密钥的风险。</p>
     */
    public void requireAdminPassword(ReauthenticationCommand command) {
        AuthContext.requireAdmin();
        String password = command == null ? null : command.password();
        UserAccount user = userRepository.findById(AuthContext.require().id())
            .orElseThrow(() -> BusinessException.unauthorized("auth.userNotFound"));
        if (password == null || password.isBlank() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // 当前会话仍有效；二次校验失败不应触发前端的登录态清理和跳转。
            throw BusinessException.forbidden("auth.secretRevealReauthenticationFailed");
        }
    }

    /** 二次验证请求只包含当前管理员密码，禁止在审计记录中保留其值。 */
    public record ReauthenticationCommand(String password) {}
}
