package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.ApiKeyCredential;
import com.baseai.platform.domain.ApiKeyRateLimitType;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.ApiKeyCredentialRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationServiceTest {
    @Mock private ApiKeyCredentialRepository repository;
    @Mock private ApiKeyEndpointCatalogService endpointCatalog;
    @Mock private ApiKeyRateLimiter rateLimiter;
    @Mock private HttpServletRequest request;

    private ApiKeySecretService secretService;
    private ApiKeyAuthenticationService service;
    private ApiKeySecretService.GeneratedApiKey generated;
    private ApiKeyCredential credential;

    /** 初始化有效 Key、绑定用户和固定客户端地址。 */
    @BeforeEach
    void setUp() throws Exception {
        PlatformProperties properties = new PlatformProperties();
        properties.getApiKey().setHashSecret("0123456789abcdef0123456789abcdef");
        secretService = new ApiKeySecretService(properties);
        generated = secretService.generate();
        credential = credential(generated);
        service = new ApiKeyAuthenticationService(repository, secretService, endpointCatalog, new ApiKeyCidrMatcher(),
            rateLimiter, new AuthUserFactory(), new ClientIpResolver());
    }

    /** 有效 Key 调用已开放且已授权接口时建立 API_KEY 身份并记录使用信息。 */
    @Test
    void authenticateAcceptsGrantedEndpoint() throws Exception {
        HandlerMethod handler = handler("allowed");
        when(endpointCatalog.resolveAnnotation(handler)).thenReturn(handler.getMethodAnnotation(ApiKeyEndpoint.class));
        when(repository.findByKeyIdAndRevokedAtIsNull(generated.keyId())).thenReturn(Optional.of(credential));
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.8");

        AuthUser user = service.authenticate(generated.rawApiKey(), request, handler);

        assertEquals(AuthenticationType.API_KEY, user.authenticationType());
        assertEquals("integration-key", user.credentialName());
        assertTrue(user.hasPermission("test:invoke"));
        assertEquals("10.0.0.8", credential.getLastUsedIp());
        verify(rateLimiter).check(99L, ApiKeyRateLimitType.MINUTE, 60);
    }

    /** 未声明开放的接口必须在查询 Key 前拒绝。 */
    @Test
    void authenticateRejectsUnannotatedEndpoint() throws Exception {
        HandlerMethod handler = handler("closed");
        when(endpointCatalog.resolveAnnotation(handler)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.authenticate(generated.rawApiKey(), request, handler));
        verify(repository, never()).findByKeyIdAndRevokedAtIsNull(generated.keyId());
    }

    /** Key 未授权当前接口时不得进入限流和业务权限阶段。 */
    @Test
    void authenticateRejectsEndpointOutsideGrant() throws Exception {
        credential.setEndpointCodes(new LinkedHashSet<>(java.util.Set.of("another.endpoint")));
        HandlerMethod handler = handler("allowed");
        when(endpointCatalog.resolveAnnotation(handler)).thenReturn(handler.getMethodAnnotation(ApiKeyEndpoint.class));
        when(repository.findByKeyIdAndRevokedAtIsNull(generated.keyId())).thenReturn(Optional.of(credential));

        assertThrows(BusinessException.class, () -> service.authenticate(generated.rawApiKey(), request, handler));
        verify(rateLimiter, never()).check(99L, ApiKeyRateLimitType.MINUTE, 60);
    }

    /** 过期、停用和来源 IP 不匹配均必须拒绝。 */
    @Test
    void authenticateRejectsExpiredDisabledAndBlockedIp() throws Exception {
        HandlerMethod handler = handler("allowed");
        when(endpointCatalog.resolveAnnotation(handler)).thenReturn(handler.getMethodAnnotation(ApiKeyEndpoint.class));
        when(repository.findByKeyIdAndRevokedAtIsNull(generated.keyId())).thenReturn(Optional.of(credential));
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.8");

        credential.setExpiresAt(Instant.now().minusSeconds(1));
        assertThrows(BusinessException.class, () -> service.authenticate(generated.rawApiKey(), request, handler));
        credential.setExpiresAt(null);
        credential.setEnabled(false);
        assertThrows(BusinessException.class, () -> service.authenticate(generated.rawApiKey(), request, handler));
        credential.setEnabled(true);
        credential.setAllowedCidrs(new LinkedHashSet<>(java.util.Set.of("192.168.1.0/24")));
        assertThrows(BusinessException.class, () -> service.authenticate(generated.rawApiKey(), request, handler));
    }

    /** 构造具备授权接口和启用用户的 API Key 实体。 */
    private ApiKeyCredential credential(ApiKeySecretService.GeneratedApiKey value) {
        UserAccount owner = new UserAccount();
        owner.setId(7L);
        owner.setUsername("service-user");
        owner.setEnabled(true);
        Menu menu = new Menu();
        menu.setPermission("test:invoke");
        menu.setEnabled(true);
        Role role = new Role();
        role.setCode("SERVICE");
        role.setEnabled(true);
        role.setMenus(java.util.Set.of(menu));
        owner.setRoles(java.util.Set.of(role));
        ApiKeyCredential result = new ApiKeyCredential();
        ReflectionTestUtils.setField(result, "id", 99L);
        result.setName("integration-key");
        result.setKeyId(value.keyId());
        result.setSecretHash(value.secretHash());
        result.setOwner(owner);
        result.setEnabled(true);
        result.setRateLimitType(ApiKeyRateLimitType.MINUTE);
        result.setRateLimitCount(60);
        result.setEndpointCodes(new LinkedHashSet<>(java.util.Set.of("test.allowed")));
        result.setAllowedCidrs(new LinkedHashSet<>());
        return result;
    }

    /** 获取测试控制器方法。 */
    private HandlerMethod handler(String name) throws Exception {
        return new HandlerMethod(new SampleController(), SampleController.class.getDeclaredMethod(name));
    }

    private static class SampleController {
        @ApiKeyEndpoint(code = "test.allowed", nameKey = "test.endpoint.name", groupKey = "test.endpoint.group")
        @RequiredPermission("test:invoke")
        public void allowed() {}
        public void closed() {}
    }
}
