package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.MailAccount;
import com.baseai.platform.domain.Role;
import com.baseai.platform.repository.MailAccountRepository;
import com.baseai.platform.repository.MailRouteRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 验证真实授权、密码加密和 Worker 请求结果，外部仓储与 HTTP 边界隔离。 */
class MailInboxServiceTest {
    private MailAccountRepository accounts;
    private RoleRepository roles;
    private ConfigCryptoService crypto;
    private MailInboxService inbox;
    private MailManagementService management;
    private MockRestServiceServer server;
    private MailAccount account;
    private Role role;

    /** 初始化真实业务服务、加密器和 HTTP 测试服务器。 */
    @BeforeEach
    void setup() {
        accounts = mock(MailAccountRepository.class);
        roles = mock(RoleRepository.class);
        var properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        crypto = new ConfigCryptoService(properties);
        var builder = RestClient.builder().baseUrl("http://worker");
        server = MockRestServiceServer.bindTo(builder).build();
        inbox = new MailInboxService(accounts, roles, crypto, builder.build());
        management = new MailManagementService(accounts, mock(MailRouteRepository.class), crypto, roles);
        role = new Role(); role.setId(7L); role.setCode("SALES"); role.setName("销售");
        account = new MailAccount();
        ReflectionTestUtils.setField(account, "id", 1L);
        account.setCode("MAIL"); account.setName("销售邮箱"); account.setHost("smtp.example.com");
        account.setPort(587); account.setUsername("a@example.com"); account.setFromAddress("a@example.com");
        account.setTlsMode("STARTTLS"); account.setPasswordEncrypted(crypto.encrypt("smtp-secret"));
        account.setImapEnabled(true); account.setImapHost("imap.example.com"); account.setImapPort(993);
        account.setImapUsername("a@example.com"); account.setImapTlsMode("SSL");
        account.setImapPasswordEncrypted(crypto.encrypt("imap-secret"));
        account.getRoles().add(role);
        when(accounts.findById(1L)).thenReturn(Optional.of(account));
        when(accounts.findAll()).thenReturn(List.of(account));
        when(accounts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        authenticate(Set.of("SALES"), Set.of("system:mail:inbox:list"));
    }

    /** 清理认证线程变量并校验所有预期远端请求。 */
    @AfterEach
    void cleanup() { AuthContext.clear(); server.verify(); }

    /** 管理员看到未绑定邮箱，多角色用户按交集看到邮箱。 */
    @Test
    void filtersAccountsAndHonorsDisabledRoles() {
        authenticate(Set.of("OTHER", "SALES"), Set.of("system:mail:inbox:list"));
        assertEquals(1, inbox.accounts().size());
        role.setEnabled(false);
        assertTrue(inbox.accounts().isEmpty());
        authenticate(Set.of("ADMIN"), Set.of());
        account.getRoles().clear(); account.setImapEnabled(false);
        assertEquals(1, inbox.accounts().size());
        assertFalse(inbox.accounts().get(0).available());
    }

    /** 越权、未登录和缺少菜单权限都不能读取邮件。 */
    @Test
    void rejectsUnauthorizedReadsBeforeCallingWorker() {
        authenticate(Set.of("OTHER"), Set.of("system:mail:inbox:list"));
        assertTrue(inbox.accounts().isEmpty());
        assertEquals(404, assertThrows(BusinessException.class, () -> inbox.read(1L, 1, 20, "4", "9")).getStatus());
        authenticate(Set.of("SALES"), Set.of());
        assertEquals(403, assertThrows(BusinessException.class, inbox::accounts).getStatus());
        AuthContext.clear();
        assertEquals(401, assertThrows(BusinessException.class, inbox::accounts).getStatus());
    }

    /** 真实序列化请求包含独立 IMAP 密码和稳定版本，响应保留正文。 */
    @Test
    void readsMessageWithIndependentCredentials() {
        server.expect(requestTo("http://worker/email/inbox"))
            .andExpect(jsonPath("$.imap.password").value("imap-secret"))
            .andExpect(jsonPath("$.uid").value("4"))
            .andExpect(jsonPath("$.uidValidity").value("9"))
            .andRespond(withSuccess("{\"body\":\"邮件正文\",\"uid\":\"4\"}", MediaType.APPLICATION_JSON));
        assertEquals("邮件正文", inbox.read(1L, 1, 20, "4", "9").get("body"));
    }

    /** 非法 UID 不会成为 IMAP 命令参数。 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "4294967296", "1:*", "1\r\nDELETE", ""})
    void rejectsInvalidUid(String uid) {
        assertThrows(BusinessException.class, () -> inbox.read(1L, 1, 20, uid, "9"));
    }

    /** 页码、分页大小、版本与启用状态均必须有效。 */
    @Test
    void rejectsInvalidPagesAndDisabledAccount() {
        assertThrows(BusinessException.class, () -> inbox.read(1L, 0, 20, null, null));
        assertThrows(BusinessException.class, () -> inbox.read(1L, 1, 51, null, null));
        assertThrows(BusinessException.class, () -> inbox.read(1L, 1, 20, "4", null));
        assertThrows(BusinessException.class, () -> inbox.read(2L, 1, 20, null, null));
        account.setEnabled(false);
        assertEquals("mail.imap.disabled", assertThrows(BusinessException.class, () -> inbox.read(1L, 1, 20, null, null)).getMessageKey());
    }

    /** Worker 返回错误时不透传敏感服务器消息。 */
    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 413, 500})
    void sanitizesWorkerFailures(int status) {
        server.expect(requestTo("http://worker/email/inbox"))
            .andRespond(withStatus(HttpStatus.valueOf(status)).body("imap-secret private server detail"));
        var exception = assertThrows(BusinessException.class, () -> inbox.read(1L, 1, 20, null, null));
        assertFalse(exception.getMessage().contains("imap-secret"));
        assertEquals(status >= 500 ? 502 : status, exception.getStatus());
    }

    /** 独立密码加密、旧请求保留配置、关闭收件不影响 SMTP。 */
    @Test
    void savesAndPreservesReceivingConfiguration() {
        authenticate(Set.of("ADMIN"), Set.of());
        when(roles.findAllById(Set.of(7L))).thenReturn(List.of(role));
        account.getRoles().clear();
        var result = management.updateAccount(1L, command(true, "new-secret", List.of(7L), "imap.example.com", 993));
        assertTrue(result.imapPasswordConfigured());
        assertEquals(List.of(7L), result.roleIds());
        assertEquals("new-secret", crypto.decrypt(account.getImapPasswordEncrypted()));
        assertNotEquals("new-secret", account.getImapPasswordEncrypted());
        management.updateAccount(1L, command(true, "", null, "imap.example.com", 993));
        assertEquals("new-secret", crypto.decrypt(account.getImapPasswordEncrypted()));
        management.updateAccount(1L, new MailManagementService.AccountCommand("MAIL", "名称", "smtp.example.com", 587,
            "a@example.com", "a@example.com", "STARTTLS", "", true));
        assertTrue(account.getImapEnabled());
        management.updateAccount(1L, command(false, "", null, null, null));
        assertFalse(account.getImapEnabled());
        assertEquals("smtp-secret", crypto.decrypt(account.getPasswordEncrypted()));
    }

    /** 普通用户不能更改角色绑定，管理员不能绑定无效角色。 */
    @Test
    void enforcesRoleBindingAdministration() {
        assertEquals(403, assertThrows(BusinessException.class,
            () -> management.updateAccount(1L, command(true, "", List.of(), "imap.example.com", 993))).getStatus());
        assertThrows(BusinessException.class, inbox::roleOptions);
        authenticate(Set.of("ADMIN"), Set.of());
        assertThrows(BusinessException.class,
            () -> management.updateAccount(1L, command(true, "", List.of(99L), "imap.example.com", 993)));
        when(roles.findAll()).thenReturn(List.of(role));
        assertEquals(7L, inbox.roleOptions().get(0).id());
    }

    /** 边界与注入配置不能保存，首次启用收件必须提供密码。 */
    @Test
    void validatesReceivingFields() {
        for (String host : List.of("", " ", "x\nLOGIN", "a".repeat(256))) {
            assertThrows(BusinessException.class, () -> management.updateAccount(1L, command(true, "", null, host, 993)));
        }
        for (int port : new int[]{0, -1, 65536}) {
            assertThrows(BusinessException.class, () -> management.updateAccount(1L, command(true, "", null, "imap.example.com", port)));
        }
        account.setImapPasswordEncrypted(null);
        assertEquals("mail.imap.passwordRequired", assertThrows(BusinessException.class,
            () -> management.updateAccount(1L, command(true, "", null, "imap.example.com", 993))).getMessageKey());
    }

    /** 构造收件配置写请求。 */
    private MailManagementService.AccountCommand command(boolean enabled, String password, List<Long> roleIds, String host, Integer port) {
        return new MailManagementService.AccountCommand("MAIL", "名称", "smtp.example.com", 587, "a@example.com",
            "a@example.com", "STARTTLS", "", true, enabled, host, port, "a@example.com", "SSL", password, roleIds);
    }

    /** 构建当前测试用户身份。 */
    private void authenticate(Set<String> roleCodes, Set<String> permissions) {
        AuthContext.set(new AuthUser(1L, "user", roleCodes, permissions, AuthenticationType.TOKEN, null, null));
    }
}
