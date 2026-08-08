package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.MailAccount;
import com.baseai.platform.domain.MailRoute;
import com.baseai.platform.repository.MailAccountRepository;
import com.baseai.platform.repository.MailRouteRepository;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailManagementServiceTest {
    private MailAccountRepository accountRepository;
    private MailRouteRepository routeRepository;
    private ConfigCryptoService cryptoService;
    private MailManagementService service;

    /** 创建使用真实 AES-GCM 加解密和模拟仓储的邮件配置服务。 */
    @BeforeEach
    void setUp() {
        accountRepository = mock(MailAccountRepository.class);
        routeRepository = mock(MailRouteRepository.class);
        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        cryptoService = new ConfigCryptoService(properties);
        service = new MailManagementService(accountRepository, routeRepository, cryptoService);
        AuthContext.set(new AuthUser(1L, "admin", Set.of("ADMIN"), Set.of(),
            AuthenticationType.TOKEN, null, null));
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(routeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** 清理线程级认证上下文，避免测试之间共享身份。 */
    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 邮箱密码必须加密保存且页面视图不得返回密码。 */
    @Test
    void encryptsPasswordAndReturnsOnlyConfiguredFlag() {
        MailManagementService.AccountView view = service.createAccount(accountCommand("app-password"));

        ArgumentCaptor<MailAccount> captor = ArgumentCaptor.forClass(MailAccount.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordEncrypted()).startsWith("enc:").doesNotContain("app-password");
        assertThat(cryptoService.decrypt(captor.getValue().getPasswordEncrypted())).isEqualTo("app-password");
        assertThat(view.passwordConfigured()).isTrue();
        assertThat(view.toString()).doesNotContain("app-password");
    }

    /** 系统管理员查询单个邮箱账户时应解密并返回原密码。 */
    @Test
    void adminCanRevealAccountPassword() {
        MailAccount account = account(true);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        MailManagementService.AccountPasswordView view = service.accountPassword(account.getId());

        assertThat(view.id()).isEqualTo(account.getId());
        assertThat(view.password()).isEqualTo("app-password");
    }

    /** 未登录用户和非管理员均不得读取邮箱明文密码。 */
    @Test
    void onlyAdminCanRevealAccountPassword() {
        AuthContext.clear();
        assertThat(assertThrows(BusinessException.class, () -> service.accountPassword(1L)).getStatus()).isEqualTo(401);

        AuthContext.set(new AuthUser(2L, "operator", Set.of("OPERATOR"), Set.of("mail:account:update"),
            AuthenticationType.TOKEN, null, null));
        BusinessException forbidden = assertThrows(BusinessException.class, () -> service.accountPassword(1L));

        assertThat(forbidden.getStatus()).isEqualTo(403);
        verify(accountRepository, never()).findById(1L);
    }

    /** 管理员查询不存在的邮箱账户密码时应返回资源不存在。 */
    @Test
    void missingAccountPasswordReturnsNotFound() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> service.accountPassword(99L));

        assertThat(exception.getStatus()).isEqualTo(404);
        assertThat(exception.getMessageKey()).isEqualTo("mail.account.notFound");
    }

    /** 编辑时空密码保留旧密文，非空密码生成可解密的新密文。 */
    @Test
    void updatesPasswordOnlyWhenProvided() {
        MailAccount account = account(true);
        String originalCiphertext = account.getPasswordEncrypted();
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.findByCode("QQ")).thenReturn(Optional.of(account));

        service.updateAccount(account.getId(), accountCommand(" "));
        assertThat(account.getPasswordEncrypted()).isEqualTo(originalCiphertext);

        service.updateAccount(account.getId(), accountCommand("new-password"));
        assertThat(account.getPasswordEncrypted()).startsWith("enc:").isNotEqualTo(originalCiphertext);
        assertThat(cryptoService.decrypt(account.getPasswordEncrypted())).isEqualTo("new-password");
    }

    /** 非法账户字段必须返回精确业务错误且不得持久化。 */
    @ParameterizedTest
    @MethodSource("invalidAccountCommands")
    void rejectsInvalidAccountFields(MailManagementService.AccountCommand command, String messageKey) {
        BusinessException exception = assertThrows(BusinessException.class, () -> service.createAccount(command));

        assertThat(exception.getMessageKey()).isEqualTo(messageKey);
        verify(accountRepository, never()).save(any());
    }

    /** 启用邮箱选项必须过滤停用账户并保持 ID 顺序。 */
    @Test
    void listsOnlyEnabledAccountOptions() {
        MailAccount disabled = account(false);
        setId(disabled, 2L);
        MailAccount enabled = account(true);
        when(accountRepository.findAll()).thenReturn(List.of(disabled, enabled));

        List<MailManagementService.AccountOption> options = service.accountOptions();

        assertThat(options).extracting(MailManagementService.AccountOption::id).containsExactly(1L);
    }

    /** 工作流邮件路由选项仅返回启用且具备可用账户和收件人的精简记录。 */
    @Test
    void listsOnlyUsableWorkflowRouteOptionsWithoutRecipientData() {
        MailAccount enabledAccount = account(true);
        MailAccount disabledAccount = account(false);
        setId(disabledAccount, 2L);
        MailRoute usable = route("ORDER", enabledAccount.getId(), true);
        MailRoute disabled = route("DISABLED", enabledAccount.getId(), false);
        MailRoute pending = route("PENDING", null, true);
        MailRoute unavailableAccount = route("NO_ACCOUNT", disabledAccount.getId(), true);
        setId(usable, 21L); setId(disabled, 22L); setId(pending, 23L); setId(unavailableAccount, 24L);
        when(routeRepository.findAll()).thenReturn(List.of(disabled, pending, unavailableAccount, usable));
        when(accountRepository.findById(enabledAccount.getId())).thenReturn(Optional.of(enabledAccount));
        when(accountRepository.findById(disabledAccount.getId())).thenReturn(Optional.of(disabledAccount));

        List<MailManagementService.RouteOption> options = service.workflowRouteOptions();

        assertThat(options).containsExactly(new MailManagementService.RouteOption(21L, "ORDER", "通知路由"));
        assertThat(options.get(0).toString()).doesNotContain("ops@example.com");
    }

    /** 被路由引用的邮箱账户不得删除。 */
    @Test
    void rejectsDeletingReferencedAccount() {
        when(accountRepository.existsById(1L)).thenReturn(true);
        when(routeRepository.findByAccountId(1L)).thenReturn(List.of(route("NOTICE", 1L, true)));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.deleteAccount(1L));

        assertThat(exception.getMessageKey()).isEqualTo("mail.account.inUse");
        verify(accountRepository, never()).deleteById(1L);
    }

    /** 具体业务路由优先于 DEFAULT，并返回解密后的内部解析结果。 */
    @Test
    void resolvesSpecificRouteBeforeDefault() {
        MailAccount account = account(true);
        MailRoute specific = route("ORDER_FAILURE", account.getId(), true);
        when(routeRepository.findByBusinessCode("ORDER_FAILURE")).thenReturn(Optional.of(specific));
        when(routeRepository.findByBusinessCode("DEFAULT")).thenReturn(Optional.of(route("DEFAULT", account.getId(), true)));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        MailManagementService.ResolvedRoute resolved = service.resolve("order_failure");

        assertThat(resolved.businessCode()).isEqualTo("ORDER_FAILURE");
        assertThat(resolved.password()).isEqualTo("app-password");
        assertThat(resolved.toString()).doesNotContain("app-password");
    }

    /** 具体路由缺失或停用时必须回退 DEFAULT。 */
    @Test
    void fallsBackToDefaultRoute() {
        MailAccount account = account(true);
        when(routeRepository.findByBusinessCode("ORDER_FAILURE"))
            .thenReturn(Optional.of(route("ORDER_FAILURE", account.getId(), false)));
        when(routeRepository.findByBusinessCode("DEFAULT"))
            .thenReturn(Optional.of(route("DEFAULT", account.getId(), true)));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThat(service.resolve("ORDER_FAILURE").businessCode()).isEqualTo("DEFAULT");
    }

    /** 人工测试必须精确解析所选路由，即使路由停用也不得回退 DEFAULT。 */
    @Test
    void resolvesSelectedRouteForManualTestWithoutFallback() {
        MailAccount account = account(true);
        MailRoute route = route("ORDER_FAILURE", account.getId(), false);
        setId(route, 12L);
        when(routeRepository.findById(12L)).thenReturn(Optional.of(route));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        MailManagementService.ResolvedRoute resolved = service.resolveRoute(12L);

        assertThat(resolved.businessCode()).isEqualTo("ORDER_FAILURE");
        assertThat(resolved.toAddresses()).containsExactly("ops@example.com");
        verify(routeRepository, never()).findByBusinessCode("DEFAULT");
    }

    /** 人工测试待配置路由时必须在调用 Worker 前返回稳定错误。 */
    @Test
    void rejectsUnconfiguredSelectedRouteForManualTest() {
        MailRoute route = route("PENDING", null, true);
        route.setToAddresses("");
        setId(route, 13L);
        when(routeRepository.findById(13L)).thenReturn(Optional.of(route));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.resolveRoute(13L));

        assertThat(exception.getMessageKey()).isEqualTo("mail.route.unavailable");
        verify(accountRepository, never()).findById(any());
    }

    /** 人工测试不存在的路由时必须返回资源不存在。 */
    @Test
    void rejectsMissingSelectedRouteForManualTest() {
        when(routeRepository.findById(99L)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> service.resolveRoute(99L));

        assertThat(exception.getStatus()).isEqualTo(404);
        assertThat(exception.getMessageKey()).isEqualTo("mail.route.notFound");
    }

    /** 人工测试不得使用已停用的 SMTP 账户。 */
    @Test
    void rejectsDisabledAccountForManualTest() {
        MailAccount account = account(false);
        MailRoute route = route("DISABLED_ACCOUNT", account.getId(), true);
        setId(route, 14L);
        when(routeRepository.findById(14L)).thenReturn(Optional.of(route));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.resolveRoute(14L));

        assertThat(exception.getMessageKey()).isEqualTo("mail.account.unavailable");
        verify(routeRepository, never()).findByBusinessCode("DEFAULT");
    }

    /** 空环境必须创建一个固定身份、始终启用且待配置的 DEFAULT 路由。 */
    @Test
    void createsUnconfiguredEnabledDefaultRoute() {
        when(routeRepository.findByBusinessCode("DEFAULT")).thenReturn(Optional.empty());

        MailManagementService.RouteView view = service.ensureDefaultRoute();

        ArgumentCaptor<MailRoute> captor = ArgumentCaptor.forClass(MailRoute.class);
        verify(routeRepository).save(captor.capture());
        assertThat(captor.getValue().getBusinessCode()).isEqualTo("DEFAULT");
        assertThat(captor.getValue().getName()).isEqualTo(MailManagementService.DEFAULT_ROUTE_NAME);
        assertThat(captor.getValue().getEnabled()).isTrue();
        assertThat(view.configured()).isFalse();
    }

    /** DEFAULT 编辑必须固定身份和启用状态，并规范化重复收件人。 */
    @Test
    void locksDefaultIdentityWhenUpdated() {
        MailAccount account = account(true);
        MailRoute route = route("DEFAULT", account.getId(), false);
        route.setName("被修改的默认名称");
        setId(route, 7L);
        when(routeRepository.findById(7L)).thenReturn(Optional.of(route));
        when(routeRepository.findByBusinessCode("DEFAULT")).thenReturn(Optional.of(route));
        when(accountRepository.existsById(account.getId())).thenReturn(true);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        MailManagementService.RouteView view = service.updateRoute(7L,
            new MailManagementService.RouteCommand("RENAMED", "新名称", account.getId(),
                List.of("first@example.com", "first@example.com", "second@example.com"), List.of(), false));

        assertThat(view.businessCode()).isEqualTo("DEFAULT");
        assertThat(view.name()).isEqualTo(MailManagementService.DEFAULT_ROUTE_NAME);
        assertThat(view.enabled()).isTrue();
        assertThat(view.toAddresses()).containsExactly("first@example.com", "second@example.com");
    }

    /** 普通路由仍可改名和停用，DEFAULT 路由则不可删除。 */
    @Test
    void keepsDefaultRestrictionsScopedToDefaultRoute() {
        MailAccount account = account(true);
        MailRoute regular = route("NOTICE", account.getId(), true);
        setId(regular, 8L);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(regular));
        when(routeRepository.findByBusinessCode("RENAMED_NOTICE")).thenReturn(Optional.empty());
        when(accountRepository.existsById(account.getId())).thenReturn(true);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        MailManagementService.RouteView updated = service.updateRoute(8L,
            new MailManagementService.RouteCommand("RENAMED_NOTICE", "新通知", account.getId(),
                List.of("ops@example.com"), List.of(), false));
        assertThat(updated.businessCode()).isEqualTo("RENAMED_NOTICE");
        assertThat(updated.name()).isEqualTo("新通知");
        assertThat(updated.enabled()).isFalse();

        when(routeRepository.findById(9L)).thenReturn(Optional.of(route("DEFAULT", account.getId(), true)));
        assertThat(assertThrows(BusinessException.class, () -> service.deleteRoute(9L)).getMessageKey())
            .isEqualTo("mail.route.defaultDeleteForbidden");
    }

    /** 空收件人、非法收件人和缺失账户均不得保存路由。 */
    @ParameterizedTest
    @MethodSource("invalidRouteCommands")
    void rejectsInvalidRouteConfiguration(MailManagementService.RouteCommand command, String messageKey) {
        when(accountRepository.existsById(1L)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.createRoute(command));

        assertThat(exception.getMessageKey()).isEqualTo(messageKey);
        verify(routeRepository, never()).save(any());
    }

    /** 待配置 DEFAULT 或停用账户不得解析为可发送路由。 */
    @Test
    void rejectsUnavailableRoutes() {
        MailRoute pending = route("DEFAULT", null, true);
        pending.setToAddresses("");
        when(routeRepository.findByBusinessCode("ORDER_FAILURE")).thenReturn(Optional.empty());
        when(routeRepository.findByBusinessCode("DEFAULT")).thenReturn(Optional.of(pending));
        assertThat(assertThrows(BusinessException.class, () -> service.resolve("ORDER_FAILURE")).getMessageKey())
            .isEqualTo("mail.route.unavailable");

        MailRoute configured = route("DEFAULT", 1L, true);
        when(routeRepository.findByBusinessCode("DEFAULT")).thenReturn(Optional.of(configured));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account(false)));
        assertThat(assertThrows(BusinessException.class, () -> service.resolve("ORDER_FAILURE")).getMessageKey())
            .isEqualTo("mail.account.unavailable");
    }

    /** 提供账户字段异常用例。 */
    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidAccountCommands() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(
                new MailManagementService.AccountCommand("bad code", "name", "smtp.example.com", 587,
                    "user", "sender@example.com", "STARTTLS", "password", true), "mail.codeInvalid"),
            org.junit.jupiter.params.provider.Arguments.of(
                new MailManagementService.AccountCommand("CODE", "name", "smtp.example.com", 0,
                    "user", "sender@example.com", "STARTTLS", "password", true), "mail.account.portInvalid"),
            org.junit.jupiter.params.provider.Arguments.of(
                new MailManagementService.AccountCommand("CODE", "name", "smtp.example.com\nattack", 587,
                    "user", "sender@example.com", "STARTTLS", "password", true), "mail.account.hostRequired"),
            org.junit.jupiter.params.provider.Arguments.of(
                new MailManagementService.AccountCommand("CODE", "name", "smtp.example.com", 587,
                    "user", "invalid", "STARTTLS", "password", true), "mail.account.fromInvalid"),
            org.junit.jupiter.params.provider.Arguments.of(
                new MailManagementService.AccountCommand("CODE", "name", "smtp.example.com", 587,
                    "user", "sender@example.com", "TLS13", "password", true), "mail.account.tlsInvalid")
        );
    }

    /** 提供路由字段异常用例。 */
    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidRouteCommands() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(
                new MailManagementService.RouteCommand("NOTICE", "通知", null,
                    List.of("ops@example.com"), List.of(), true), "mail.account.notFound"),
            org.junit.jupiter.params.provider.Arguments.of(
                new MailManagementService.RouteCommand("NOTICE", "通知", 1L,
                    List.of(), List.of(), true), "mail.route.recipientRequired"),
            org.junit.jupiter.params.provider.Arguments.of(
                new MailManagementService.RouteCommand("NOTICE", "通知", 1L,
                    List.of("bad-address"), List.of(), true), "mail.route.recipientInvalid"),
            org.junit.jupiter.params.provider.Arguments.of(
                new MailManagementService.RouteCommand("NOTICE", "通知", 1L,
                    List.of("ops@example.com"), List.of("cc@example.com\nBcc:evil@example.com"), true),
                "mail.route.recipientInvalid")
        );
    }

    /** 创建标准账户命令。 */
    private MailManagementService.AccountCommand accountCommand(String password) {
        return new MailManagementService.AccountCommand("qq", "QQ 邮箱", "smtp.qq.com", 587,
            "sender@example.com", "sender@example.com", "starttls", password, true);
    }

    /** 创建测试邮箱实体。 */
    private MailAccount account(boolean enabled) {
        MailAccount account = new MailAccount();
        account.setCode("QQ");
        account.setName("QQ 邮箱");
        account.setHost("smtp.qq.com");
        account.setPort(587);
        account.setUsername("sender@example.com");
        account.setFromAddress("sender@example.com");
        account.setTlsMode("STARTTLS");
        account.setPasswordEncrypted(cryptoService.encrypt("app-password"));
        account.setEnabled(enabled);
        setId(account, 1L);
        return account;
    }

    /** 创建指向指定邮箱的测试路由。 */
    private MailRoute route(String code, Long accountId, boolean enabled) {
        MailRoute route = new MailRoute();
        route.setBusinessCode(code);
        route.setName("通知路由");
        route.setAccountId(accountId);
        route.setToAddresses("ops@example.com");
        route.setCcAddresses("");
        route.setEnabled(enabled);
        return route;
    }

    /** 为测试实体设置主键。 */
    private void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
