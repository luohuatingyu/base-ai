package com.baseai.platform.controller;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.MailAccount;
import com.baseai.platform.repository.MailAccountRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.security.*;
import com.baseai.platform.service.MailInboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证收件接口真实服务输出、缓存策略和权限声明。 */
class MailInboxControllerTest {
    /** 清除当前登录身份。 */
    @AfterEach
    void clear() { AuthContext.clear(); }

    /** 收件账户响应仅包含展示数据，并禁止浏览器缓存。 */
    @Test
    void returnsSafeAccountOptionsWithoutCaching() throws Exception {
        var accounts = mock(MailAccountRepository.class);
        var account = new MailAccount();
        ReflectionTestUtils.setField(account, "id", 1L);
        account.setName("销售邮箱"); account.setFromAddress("sales@example.com");
        account.setImapPasswordEncrypted("secret-cipher"); account.setImapHost("private-config.example.com");
        when(accounts.findAll()).thenReturn(List.of(account));
        var properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var service = new MailInboxService(accounts, mock(RoleRepository.class), new ConfigCryptoService(properties), RestClient.create());
        AuthContext.set(new AuthUser(1L, "admin", Set.of("ADMIN"), Set.of(), AuthenticationType.TOKEN, null, null));
        var mvc = MockMvcBuilders.standaloneSetup(new MailInboxController(service)).build();
        mvc.perform(get("/api/mail/inbox/accounts"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private"))
            .andExpect(jsonPath("$[0].name").value("销售邮箱"))
            .andExpect(jsonPath("$[0].available").value(false))
            .andExpect(jsonPath("$[0].imapPasswordEncrypted").doesNotExist())
            .andExpect(jsonPath("$[0].imapHost").doesNotExist());
    }

    /** 列表与详情均使用同一收件权限，防止仅前端过滤。 */
    @Test
    void declaresReadPermissionForAllInboxEndpoints() {
        for (String name : List.of("accounts", "messages", "message")) {
            var method = Arrays.stream(MailInboxController.class.getDeclaredMethods())
                .filter(item -> item.getName().equals(name)).findFirst().orElseThrow();
            assertEquals("system:mail:inbox:list", method.getAnnotation(RequiredPermission.class).value());
        }
    }
}
