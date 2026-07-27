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
        if (!(handler instanceof HandlerMethod method)) throw BusinessException.forbidden("apiKey.resourceForbidden");
        ApiKeyEndpoint endpoint = endpointCatalog.resolveAnnotation(method);
        if (endpoint == null) throw BusinessException.forbidden("apiKey.endpointForbidden");

        ApiKeySecretService.ParsedApiKey parsed = secretService.parse(rawApiKey);
        ApiKeyCredential credential = repository.findByKeyIdAndRevokedAtIsNull(parsed.keyId())
            .orElseThrow(() -> BusinessException.unauthorized("apiKey.invalid"));
        Instant now = Instant.now();
        if (!secretService.matches(parsed.secret(), credential.getSecretHash())
            || !Boolean.TRUE.equals(credential.getEnabled())
            || credential.getExpiresAt() != null && !credential.getExpiresAt().isAfter(now)) {
            throw BusinessException.unauthorized("apiKey.invalid");
        }
        UserAccount owner = credential.getOwner();
        if (owner == null || !Boolean.TRUE.equals(owner.getEnabled())) throw BusinessException.unauthorized("apiKey.invalid");
        if (!credential.getEndpointCodes().contains(endpoint.code())) throw BusinessException.forbidden("apiKey.endpointUnauthorized");

        String clientIp = clientIpResolver.resolve(request);
        if (!cidrMatcher.matches(clientIp, credential.getAllowedCidrs())) throw BusinessException.forbidden("apiKey.ipForbidden");
        rateLimiter.check(credential.getId(), credential.getRateLimitType(), credential.getRateLimitCount());
        credential.setLastUsedAt(now);
        credential.setLastUsedIp(clientIp);
        return authUserFactory.fromApiKey(owner, credential.getId(), credential.getName());
    }
}
