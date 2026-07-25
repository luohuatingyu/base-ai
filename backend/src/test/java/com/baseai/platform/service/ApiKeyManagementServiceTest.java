package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.ApiKeyCredential;
import com.baseai.platform.domain.ApiKeyRateLimitType;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.ApiKeyCredentialRepository;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.ApiKeyCidrMatcher;
import com.baseai.platform.security.ApiKeyEndpointCatalogService;
import com.baseai.platform.security.ApiKeySecretService;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyManagementServiceTest {
    @Mock private ApiKeyCredentialRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private ApiKeyEndpointCatalogService endpointCatalog;
    private ApiKeyManagementService service;

    /** 初始化管理服务、操作用户和可开放接口。 */
    @BeforeEach
    void setUp() {
        PlatformProperties properties = new PlatformProperties();
        properties.getApiKey().setHashSecret("0123456789abcdef0123456789abcdef");
        service = new ApiKeyManagementService(repository, userRepository, new ApiKeySecretService(properties),
            endpointCatalog, new ApiKeyCidrMatcher());
        AuthContext.set(new AuthUser(1L, "admin", Set.of("ADMIN"), Set.of(), AuthenticationType.TOKEN, null, null));
        UserAccount owner = new UserAccount();
        owner.setId(2L);
        owner.setUsername("service-user");
        owner.setDisplayName("服务用户");
        owner.setEnabled(true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(owner));
    }

    /** 清理线程级认证上下文。 */
    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 永久有效 Key 应保存空过期时间并仅在创建响应返回明文。 */
    @Test
    void createSupportsNeverExpires() {
        when(endpointCatalog.contains("ai.chat.invoke")).thenReturn(true);
        when(repository.save(any(ApiKeyCredential.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApiKeyManagementService.CreatedApiKey created = service.create(command(true, null));

        assertNull(created.item().expiresAt());
        assertTrue(created.item().neverExpires());
        assertTrue(created.apiKey().matches("sk-[A-Za-z0-9]{32}"));
        assertEquals(ApiKeyRateLimitType.MINUTE, created.item().rateLimitType());
        assertEquals(120, created.item().rateLimitCount());
    }

    /** 支持每秒、每小时和每天限流配置。 */
    @Test
    void createSupportsFlexibleRateLimitPeriods() {
        when(endpointCatalog.contains("ai.chat.invoke")).thenReturn(true);
        when(repository.save(any(ApiKeyCredential.class))).thenAnswer(invocation -> invocation.getArgument(0));

        for (ApiKeyRateLimitType type : Set.of(ApiKeyRateLimitType.SECOND, ApiKeyRateLimitType.HOUR,
            ApiKeyRateLimitType.DAY)) {
            ApiKeyManagementService.CreatedApiKey created = service.create(command(type, 25));
            assertEquals(type, created.item().rateLimitType());
            assertEquals(25, created.item().rateLimitCount());
            assertNull(created.item().rateLimitPerMinute());
        }
    }

    /** 无限制模式不保存调用次数。 */
    @Test
    void createSupportsUnlimitedRateLimit() {
        when(endpointCatalog.contains("ai.chat.invoke")).thenReturn(true);
        when(repository.save(any(ApiKeyCredential.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApiKeyManagementService.CreatedApiKey created = service.create(command(ApiKeyRateLimitType.UNLIMITED, null));

        assertEquals(ApiKeyRateLimitType.UNLIMITED, created.item().rateLimitType());
        assertNull(created.item().rateLimitCount());
    }

    /** 历史每分钟字段应继续映射为分钟周期配置。 */
    @Test
    void createSupportsLegacyPerMinuteField() {
        when(endpointCatalog.contains("ai.chat.invoke")).thenReturn(true);
        when(repository.save(any(ApiKeyCredential.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ApiKeyManagementService.ApiKeyCommand command = new ApiKeyManagementService.ApiKeyCommand(
            "integration", 2L, true, true, null, null, null, 90,
            Set.of("ai.chat.invoke"), Set.of());

        ApiKeyManagementService.CreatedApiKey created = service.create(command);

        assertEquals(ApiKeyRateLimitType.MINUTE, created.item().rateLimitType());
        assertEquals(90, created.item().rateLimitCount());
        assertEquals(90, created.item().rateLimitPerMinute());
    }

    /** 受限模式校验次数边界，无限制模式拒绝多余次数。 */
    @Test
    void createRejectsInvalidRateLimitConfiguration() {
        assertThrows(BusinessException.class, () -> service.create(command(ApiKeyRateLimitType.SECOND, null)));
        assertThrows(BusinessException.class, () -> service.create(command(ApiKeyRateLimitType.MINUTE, 0)));
        assertThrows(BusinessException.class, () -> service.create(command(ApiKeyRateLimitType.HOUR, 100001)));
        assertThrows(BusinessException.class, () -> service.create(command(ApiKeyRateLimitType.UNLIMITED, 1)));
    }

    /** 指定有效期必须存在且晚于当前时间。 */
    @Test
    void createRejectsInvalidScheduledExpiration() {
        assertThrows(BusinessException.class, () -> service.create(command(false, null)));
        assertThrows(BusinessException.class, () -> service.create(command(false, Instant.now().minusSeconds(1))));
    }

    /** 永久有效和指定过期时间不得同时提交。 */
    @Test
    void createRejectsConflictingExpirationSettings() {
        assertThrows(BusinessException.class, () -> service.create(command(true, Instant.now().plusSeconds(3600))));
    }

    /** 构造覆盖接口授权、IP 白名单和限流的管理命令。 */
    private ApiKeyManagementService.ApiKeyCommand command(boolean neverExpires, Instant expiresAt) {
        return new ApiKeyManagementService.ApiKeyCommand("integration", 2L, true, neverExpires, expiresAt,
            ApiKeyRateLimitType.MINUTE, 120, null, Set.of("ai.chat.invoke"), Set.of("10.0.0.0/24"));
    }

    /** 构造指定调用周期和次数的管理命令。 */
    private ApiKeyManagementService.ApiKeyCommand command(ApiKeyRateLimitType type, Integer count) {
        return new ApiKeyManagementService.ApiKeyCommand("integration", 2L, true, true, null,
            type, count, null, Set.of("ai.chat.invoke"), Set.of("10.0.0.0/24"));
    }
}
