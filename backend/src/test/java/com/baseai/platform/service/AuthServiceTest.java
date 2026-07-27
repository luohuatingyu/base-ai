package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.SessionService;
import com.baseai.platform.security.TokenClaims;
import com.baseai.platform.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private UserRepository userRepository;
    private BCryptPasswordEncoder passwordEncoder;
    private TokenService tokenService;
    private SessionService sessionService;
    private LoginAuditService loginAuditService;
    private AuthService service;
    private AuthService.LoginMetadata metadata;

    /** 为每个认证场景创建隔离依赖，避免写入真实会话和审计日志。 */
    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(BCryptPasswordEncoder.class);
        tokenService = mock(TokenService.class);
        sessionService = mock(SessionService.class);
        loginAuditService = mock(LoginAuditService.class);
        service = new AuthService(userRepository, passwordEncoder, tokenService, sessionService, loginAuditService);
        metadata = new AuthService.LoginMetadata("127.0.0.1", "test-agent");
    }

    /** 登录成功必须保存稳定消息键，不能把当前语言文本写入审计记录。 */
    @Test
    void successfulLoginStoresStableMessageKey() {
        UserAccount user = enabledUser();
        TokenClaims claims = new TokenClaims(1L, "admin", "token-id", Instant.now().plusSeconds(300));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        when(tokenService.createToken(1L, "admin")).thenReturn("token");
        when(tokenService.parseToken("token")).thenReturn(claims);

        AuthService.LoginResult result = service.login("admin", "secret", metadata);

        assertEquals("token", result.token());
        verify(loginAuditService).save("admin", metadata, true, "auth.loginSuccess");
    }

    /** 业务认证失败必须保留可翻译且不泄露账号存在性的消息键。 */
    @Test
    void businessFailureStoresBusinessMessageKey() {
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.login(" missing ", "secret", metadata));

        verify(loginAuditService).save("missing", metadata, false, "auth.invalidCredentials");
    }

    /** 非预期内部异常只能记录通用失败键，不能把内部异常正文暴露到登录日志。 */
    @Test
    void unexpectedFailureStoresSafeGenericKey() {
        UserAccount user = enabledUser();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        when(tokenService.createToken(1L, "admin")).thenThrow(new IllegalStateException("sensitive internal detail"));

        assertThrows(IllegalStateException.class, () -> service.login("admin", "secret", metadata));

        verify(loginAuditService).save("admin", metadata, false, "auth.loginFailed");
        verify(loginAuditService, never()).save("admin", metadata, false, "sensitive internal detail");
    }

    /** 创建可通过密码和状态校验的最小用户实体。 */
    private UserAccount enabledUser() {
        UserAccount user = new UserAccount();
        user.setId(1L);
        user.setUsername("admin");
        user.setDisplayName("系统管理员");
        user.setPasswordHash("hash");
        user.setEnabled(true);
        return user;
    }
}
