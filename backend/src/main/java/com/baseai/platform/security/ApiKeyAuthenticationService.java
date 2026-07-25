package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.ApiKeyCredential;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.ApiKeyCredentialRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.HandlerMethod;

import java.time.Instant;

@Service
public class ApiKeyAuthenticationService {
    private final ApiKeyCredentialRepository repository;
    private final ApiKeySecretService secretService;
    private final ApiKeyEndpointCatalogService endpointCatalog;
    private final ApiKeyCidrMatcher cidrMatcher;
    private final ApiKeyRateLimiter rateLimiter;
    private final AuthUserFactory authUserFactory;
    private final ClientIpResolver clientIpResolver;

    public ApiKeyAuthenticationService(ApiKeyCredentialRepository repository, ApiKeySecretService secretService,
                                       ApiKeyEndpointCatalogService endpointCatalog, ApiKeyCidrMatcher cidrMatcher,
                                       ApiKeyRateLimiter rateLimiter, AuthUserFactory authUserFactory,
                                       ClientIpResolver clientIpResolver) {
        this.repository = repository;
        this.secretService = secretService;
        this.endpointCatalog = endpointCatalog;
        this.cidrMatcher = cidrMatcher;
        this.rateLimiter = rateLimiter;
        this.authUserFactory = authUserFactory;
        this.clientIpResolver = clientIpResolver;
    }

    /** 校验 API Key、开放接口、来源地址和限流后建立绑定用户身份。 */
    @Transactional
    public AuthUser authenticate(String rawApiKey, HttpServletRequest request, Object handler) {
        if (!(handler instanceof HandlerMethod method)) throw BusinessException.forbidden("API Key 不允许访问该资源");
        ApiKeyEndpoint endpoint = endpointCatalog.resolveAnnotation(method);
        if (endpoint == null) throw BusinessException.forbidden("API Key 不允许访问该接口");

        ApiKeySecretService.ParsedApiKey parsed = secretService.parse(rawApiKey);
        ApiKeyCredential credential = repository.findByKeyIdAndRevokedAtIsNull(parsed.keyId())
            .orElseThrow(() -> BusinessException.unauthorized("API Key 无效"));
        Instant now = Instant.now();
        if (!secretService.matches(parsed.secret(), credential.getSecretHash())
            || !Boolean.TRUE.equals(credential.getEnabled())
            || credential.getExpiresAt() != null && !credential.getExpiresAt().isAfter(now)) {
            throw BusinessException.unauthorized("API Key 无效");
        }
        UserAccount owner = credential.getOwner();
        if (owner == null || !Boolean.TRUE.equals(owner.getEnabled())) throw BusinessException.unauthorized("API Key 无效");
        if (!credential.getEndpointCodes().contains(endpoint.code())) throw BusinessException.forbidden("API Key 未授权当前接口");

        String clientIp = clientIpResolver.resolve(request);
        if (!cidrMatcher.matches(clientIp, credential.getAllowedCidrs())) throw BusinessException.forbidden("API Key 来源地址不允许");
        rateLimiter.check(credential.getId(), credential.getRateLimitType(), credential.getRateLimitCount());
        credential.setLastUsedAt(now);
        credential.setLastUsedIp(clientIp);
        return authUserFactory.fromApiKey(owner, credential.getId(), credential.getName());
    }
}
