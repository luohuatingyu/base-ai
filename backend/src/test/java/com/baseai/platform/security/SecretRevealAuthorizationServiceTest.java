package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecretRevealAuthorizationServiceTest {
    /** 每个用例结束后清理线程认证状态。 */
    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 管理员输入当前账户的正确密码后才可继续读取可逆保存的凭据。 */
    @Test
    void acceptsCurrentAdministratorPassword() {
        UserRepository users = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        UserAccount user = user(7L, "hash");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(encoder.matches("Correct-Password-123!", "hash")).thenReturn(true);
        AuthContext.set(admin(7L));

        assertDoesNotThrow(() -> new SecretRevealAuthorizationService(users, encoder)
            .requireAdminPassword(new SecretRevealAuthorizationService.ReauthenticationCommand("Correct-Password-123!")));
    }

    /** 密码错误不能清理有效会话，而应返回明确的禁止访问结果。 */
    @Test
    void rejectsIncorrectPasswordWithoutTreatingSessionAsExpired() {
        UserRepository users = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "hash")));
        when(encoder.matches("incorrect", "hash")).thenReturn(false);
        AuthContext.set(admin(7L));

        BusinessException exception = assertThrows(BusinessException.class, () ->
            new SecretRevealAuthorizationService(users, encoder)
                .requireAdminPassword(new SecretRevealAuthorizationService.ReauthenticationCommand("incorrect")));

        assertEquals(403, exception.getStatus());
        assertEquals("auth.secretRevealReauthenticationFailed", exception.getMessageKey());
    }

    /** 非管理员即使知道自身密码也不得读取平台长期凭据。 */
    @Test
    void rejectsNonAdministratorBeforePasswordLookup() {
        AuthContext.set(new AuthUser(8L, "operator", Set.of("OPERATOR"), Set.of(), AuthenticationType.TOKEN, null, null));

        BusinessException exception = assertThrows(BusinessException.class, () ->
            new SecretRevealAuthorizationService(mock(UserRepository.class), mock(BCryptPasswordEncoder.class))
                .requireAdminPassword(new SecretRevealAuthorizationService.ReauthenticationCommand("password")));

        assertEquals(403, exception.getStatus());
        assertEquals("auth.adminRoleRequired", exception.getMessageKey());
    }

    /** 创建带最小字段的当前管理员实体。 */
    private static UserAccount user(Long id, String hash) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setPasswordHash(hash);
        return user;
    }

    /** 创建管理员认证快照。 */
    private static AuthUser admin(Long id) {
        return new AuthUser(id, "admin", Set.of("ADMIN"), Set.of(), AuthenticationType.TOKEN, null, null);
    }
}
