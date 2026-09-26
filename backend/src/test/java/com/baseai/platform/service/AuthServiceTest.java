package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.SessionService;
import com.baseai.platform.security.LoginAttemptService;
import com.baseai.platform.security.TokenClaims;
import com.baseai.platform.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Department;
import java.util.Set;
import java.util.List;
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
    private LoginAttemptService loginAttemptService;
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
        loginAttemptService = mock(LoginAttemptService.class);
        service = new AuthService(userRepository, passwordEncoder, tokenService, sessionService, loginAuditService,
            loginAttemptService);
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
        verify(loginAttemptService).checkAllowed("admin", "127.0.0.1");
        verify(loginAttemptService).clearAccountFailures("admin", "127.0.0.1");
        verify(loginAuditService).save("admin", metadata, true, "auth.loginSuccess");
    }

    /** 业务认证失败必须保留可翻译且不泄露账号存在性的消息键。 */
    @Test
    void businessFailureStoresBusinessMessageKey() {
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.login(" missing ", "secret", metadata));

        verify(loginAuditService).save("missing", metadata, false, "auth.invalidCredentials");
        verify(loginAttemptService).recordFailure("missing", "127.0.0.1");
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
        verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
    }

    /** 已处于封禁期的来源必须在查询账号前被拒绝。 */
    @Test
    void rateLimitedLoginDoesNotQueryCredentials() {
        doThrow(new BusinessException(429, "auth.loginRateLimited"))
            .when(loginAttemptService).checkAllowed("admin", "127.0.0.1");

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.login("admin", "secret", metadata));

        assertEquals(429, exception.getStatus());
        verify(userRepository, never()).findByUsername(anyString());
        verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
        verify(loginAuditService).save("admin", metadata, false, "auth.loginRateLimited");
    }

    /** 认证上下文不应泄漏到下一个用例。 */
    @AfterEach
    void clearContext() { AuthContext.clear(); }

    /** 空账号和密码在读取凭据之前拒绝，不签发会话。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsBlankCredentials(String value) {
        assertThrows(BusinessException.class, () -> service.login(value, "secret", metadata));
        assertThrows(BusinessException.class, () -> service.login("admin", value, metadata));
        verifyNoInteractions(tokenService, sessionService);
    }

    /** 密码错误必须计入失败次数，不能颁发任何令牌。 */
    @Test
    void wrongPasswordRecordsFailureWithoutSession() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(enabledUser()));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        BusinessException error = assertThrows(BusinessException.class, () -> service.login("admin", "wrong", metadata));
        assertEquals("auth.invalidCredentials", error.getMessageKey());
        verify(loginAttemptService).recordFailure("admin", "127.0.0.1");
        verifyNoInteractions(tokenService, sessionService);
    }

    /** 已通过密码验证的停用账户不能登录，也不能算作密码失败。 */
    @ParameterizedTest
    @ValueSource(booleans = {false})
    @org.junit.jupiter.params.provider.NullSource
    void disabledAccountDoesNotCreateSession(Boolean enabled) {
        UserAccount user = enabledUser(); user.setEnabled(enabled);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        BusinessException error = assertThrows(BusinessException.class, () -> service.login("admin", "secret", metadata));
        assertEquals(403, error.getStatus());
        assertEquals("auth.accountDisabled", error.getMessageKey());
        verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
        verifyNoInteractions(tokenService, sessionService);
    }

    /** 失败计数依赖异常时采用通用审计信息，避免暴露内部故障。 */
    @Test
    void failureCounterOutageDoesNotLeakAuditDetails() {
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("secret cache detail")).when(loginAttemptService).recordFailure("missing", "127.0.0.1");
        assertThrows(IllegalStateException.class, () -> service.login("missing", "wrong", metadata));
        verify(loginAuditService).save("missing", metadata, false, "auth.loginFailed");
        verifyNoInteractions(tokenService, sessionService);
    }

    /** 登出必须同时撤销令牌和移除在线会话。 */
    @Test
    void logoutRevokesTokenAndRemovesSession() {
        TokenClaims claims = new TokenClaims(1L, "admin", "token-id", Instant.now().plusSeconds(300));
        when(tokenService.parseToken("token")).thenReturn(claims);
        service.logout("token");
        verify(tokenService).revokeTokenId(claims.tokenId(), claims.expiresAt());
        verify(sessionService).remove(claims);
    }

    /** 当前用户权限快照必须过滤停用角色、菜单，去重权限并稳定排序。 */
    @Test
    void currentUserFiltersDisabledRolesAndMenus() {
        UserAccount user = enabledUser();
        Department department = new Department(); department.setId(9L); user.setDepartment(department);
        Menu allowed = menu(2L, "ai:chat:invoke", true);
        Menu disabled = menu(3L, "system:user:list", false);
        Menu folder = menu(1L, null, true);
        Role role = new Role(); role.setCode("USER"); role.setMenus(Set.of(allowed, disabled, folder));
        Role inactive = new Role(); inactive.setCode("ADMIN"); inactive.setEnabled(false); inactive.setMenus(Set.of(disabled));
        user.setRoles(Set.of(role, inactive));
        AuthContext.set(new AuthUser(1L, "admin", Set.of(), Set.of(), com.baseai.platform.security.AuthenticationType.TOKEN, null, null));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        AuthService.CurrentUser result = service.currentUser();
        assertEquals(List.of("USER"), result.roles());
        assertEquals(List.of("ai:chat:invoke"), result.permissions());
        assertEquals(List.of(1L, 2L), result.menus().stream().map(AuthService.MenuItem::id).toList());
        assertEquals(9L, result.departmentId());
    }

    /** 已删除账号不能依靠残留认证上下文获取用户数据。 */
    @Test
    void currentUserRejectsDeletedAccount() {
        AuthContext.set(new AuthUser(1L, "admin", Set.of(), Set.of(), com.baseai.platform.security.AuthenticationType.TOKEN, null, null));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        BusinessException error = assertThrows(BusinessException.class, service::currentUser);
        assertEquals(401, error.getStatus());
        assertEquals("auth.userNotFound", error.getMessageKey());
    }

    /** 构造有明确排序和权限的菜单实体。 */
    private Menu menu(long id, String permission, boolean enabled) {
        Menu menu = new Menu(); menu.setId(id); menu.setSortOrder(0); menu.setPermission(permission); menu.setEnabled(enabled);
        return menu;
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
