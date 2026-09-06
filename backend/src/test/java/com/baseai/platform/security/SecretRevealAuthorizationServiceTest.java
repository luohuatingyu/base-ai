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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        SecretRevealAttemptService attempts = mock(SecretRevealAttemptService.class);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(encoder.matches("Correct-Password-123!", "hash")).thenReturn(true);
        AuthContext.set(admin(7L));

        assertDoesNotThrow(() -> new SecretRevealAuthorizationService(users, encoder, attempts)
            .requireAdminPassword(new SecretRevealAuthorizationService.ReauthenticationCommand("Correct-Password-123!")));
        verify(attempts).checkAllowed(7L);
        verify(attempts).clearFailures(7L);
        verify(attempts, never()).recordFailure(7L);
    }

    /** 密码错误不能清理有效会话，而应返回明确的禁止访问结果。 */
    @Test
    void rejectsIncorrectPasswordWithoutTreatingSessionAsExpired() {
        UserRepository users = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        SecretRevealAttemptService attempts = mock(SecretRevealAttemptService.class);
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, "hash")));
        when(encoder.matches("incorrect", "hash")).thenReturn(false);
        AuthContext.set(admin(7L));

        BusinessException exception = assertThrows(BusinessException.class, () ->
            new SecretRevealAuthorizationService(users, encoder, attempts)
                .requireAdminPassword(new SecretRevealAuthorizationService.ReauthenticationCommand("incorrect")));

        assertEquals(403, exception.getStatus());
        assertEquals("auth.secretRevealReauthenticationFailed", exception.getMessageKey());
        verify(attempts).recordFailure(7L);
        verify(attempts, never()).clearFailures(7L);
    }

    /** 非管理员即使知道自身密码也不得读取平台长期凭据。 */
    @Test
    void rejectsNonAdministratorBeforePasswordLookup() {
        UserRepository users = mock(UserRepository.class);
        SecretRevealAttemptService attempts = mock(SecretRevealAttemptService.class);
        AuthContext.set(new AuthUser(8L, "operator", Set.of("OPERATOR"), Set.of(), AuthenticationType.TOKEN, null, null));

        BusinessException exception = assertThrows(BusinessException.class, () ->
            new SecretRevealAuthorizationService(users, mock(BCryptPasswordEncoder.class), attempts)
                .requireAdminPassword(new SecretRevealAuthorizationService.ReauthenticationCommand("password")));

        assertEquals(403, exception.getStatus());
        assertEquals("auth.adminRoleRequired", exception.getMessageKey());
        verifyNoInteractions(users, attempts);
    }

    /** 限流服务拒绝请求时不得查询密码散列。 */
    @Test
    void rejectsRateLimitedAdministratorBeforePasswordLookup() {
        UserRepository users = mock(UserRepository.class);
        SecretRevealAttemptService attempts = mock(SecretRevealAttemptService.class);
        org.mockito.Mockito.doThrow(new BusinessException(429, "auth.secretRevealRateLimited"))
            .when(attempts).checkAllowed(7L);
        AuthContext.set(admin(7L));

        BusinessException exception = assertThrows(BusinessException.class, () ->
            new SecretRevealAuthorizationService(users, mock(BCryptPasswordEncoder.class), attempts)
                .requireAdminPassword(new SecretRevealAuthorizationService.ReauthenticationCommand("password")));

        assertEquals(429, exception.getStatus());
        verifyNoInteractions(users);
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
