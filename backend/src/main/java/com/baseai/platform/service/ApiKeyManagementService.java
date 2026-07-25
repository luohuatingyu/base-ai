package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.ApiKeyCredential;
import com.baseai.platform.domain.ApiKeyRateLimitType;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.ApiKeyCredentialRepository;
import com.baseai.platform.repository.UserRepository;
import com.baseai.platform.security.ApiKeyCidrMatcher;
import com.baseai.platform.security.ApiKeyEndpointCatalogService;
import com.baseai.platform.security.ApiKeySecretService;
import com.baseai.platform.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ApiKeyManagementService {
    private final ApiKeyCredentialRepository repository;
    private final UserRepository userRepository;
    private final ApiKeySecretService secretService;
    private final ApiKeyEndpointCatalogService endpointCatalog;
    private final ApiKeyCidrMatcher cidrMatcher;

    public ApiKeyManagementService(ApiKeyCredentialRepository repository, UserRepository userRepository,
                                   ApiKeySecretService secretService, ApiKeyEndpointCatalogService endpointCatalog,
                                   ApiKeyCidrMatcher cidrMatcher) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.secretService = secretService;
        this.endpointCatalog = endpointCatalog;
        this.cidrMatcher = cidrMatcher;
    }

    /** 分页查询未吊销的 API Key。 */
    @Transactional(readOnly = true)
    public PlatformAdminService.PageResult<ApiKeyView> list(String keyword, Boolean enabled, int page, int size) {
        List<ApiKeyView> rows = repository.findAll().stream()
            .filter(item -> item.getRevokedAt() == null)
            .filter(item -> keyword == null || keyword.isBlank()
                || item.getName().toLowerCase().contains(keyword.trim().toLowerCase())
                || item.getKeyId().contains(keyword.trim().toLowerCase()))
            .filter(item -> enabled == null || enabled.equals(item.getEnabled()))
            .sorted(Comparator.comparing(ApiKeyCredential::getId).reversed())
            .map(this::toView).toList();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int from = Math.min((safePage - 1) * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        return new PlatformAdminService.PageResult<>(rows.subList(from, to), rows.size(), safePage, safeSize);
    }

    /** 返回可绑定 API Key 的启用用户。 */
    @Transactional(readOnly = true)
    public List<OwnerView> owners() {
        return userRepository.findAll().stream().filter(user -> Boolean.TRUE.equals(user.getEnabled()))
            .sorted(Comparator.comparing(UserAccount::getUsername))
            .map(user -> new OwnerView(user.getId(), user.getUsername(), user.getDisplayName())).toList();
    }

    /** 返回代码显式声明的 API Key 接口目录。 */
    public List<ApiKeyEndpointCatalogService.EndpointView> endpoints() {
        return endpointCatalog.catalog();
    }

    /** 创建 API Key 并仅在本次响应返回完整明文。 */
    @Transactional
    public CreatedApiKey create(ApiKeyCommand command) {
        ApiKeyCredential credential = new ApiKeyCredential();
        apply(credential, command);
        ApiKeySecretService.GeneratedApiKey generated = secretService.generate();
        credential.setKeyId(generated.keyId());
        credential.setSecretHash(generated.secretHash());
        credential.setCreatedBy(AuthContext.require().id());
        repository.save(credential);
        return new CreatedApiKey(toView(credential), generated.rawApiKey());
    }

    /** 更新 API Key 元数据和授权范围但不改变 Secret。 */
    @Transactional
    public ApiKeyView update(Long id, ApiKeyCommand command) {
        ApiKeyCredential credential = requireCredential(id);
        apply(credential, command);
        return toView(credential);
    }

    /** 生成新 Secret 并立即使旧 API Key 失效。 */
    @Transactional
    public RotatedApiKey rotate(Long id) {
        ApiKeyCredential credential = requireCredential(id);
        ApiKeySecretService.GeneratedApiKey generated = secretService.generate();
        credential.setKeyId(generated.keyId());
        credential.setSecretHash(generated.secretHash());
        credential.setLastUsedAt(null);
        credential.setLastUsedIp(null);
        return new RotatedApiKey(toView(credential), generated.rawApiKey());
    }

    /** 启用或停用指定 API Key。 */
    @Transactional
    public ApiKeyView changeEnabled(Long id, boolean enabled) {
        ApiKeyCredential credential = requireCredential(id);
        credential.setEnabled(enabled);
        return toView(credential);
    }

    /** 永久吊销 API Key 并保留审计关联。 */
    @Transactional
    public void revoke(Long id) {
        ApiKeyCredential credential = requireCredential(id);
        credential.setEnabled(false);
        credential.setRevokedAt(Instant.now());
    }

    /** 应用并校验管理页面提交的 API Key 配置。 */
    private void apply(ApiKeyCredential credential, ApiKeyCommand command) {
        if (command == null) throw new BusinessException("API Key 配置不能为空");
        credential.setName(requireText(command.name(), "请输入 API Key 名称", 100));
        if (command.ownerUserId() == null) throw new BusinessException("请选择绑定用户");
        UserAccount owner = userRepository.findById(command.ownerUserId())
            .orElseThrow(() -> BusinessException.notFound("绑定用户不存在"));
        if (!Boolean.TRUE.equals(owner.getEnabled())) throw new BusinessException("绑定用户已停用");
        credential.setOwner(owner);
        credential.setEnabled(command.enabled() == null || command.enabled());
        credential.setExpiresAt(resolveExpiresAt(command.neverExpires(), command.expiresAt()));
        RateLimitConfiguration rateLimit = resolveRateLimit(command);
        credential.setRateLimitType(rateLimit.type());
        credential.setRateLimitCount(rateLimit.count());
        if (rateLimit.type() == ApiKeyRateLimitType.MINUTE) credential.setRateLimitPerMinute(rateLimit.count());
        credential.setEndpointCodes(resolveEndpointCodes(command.endpointCodes()));
        credential.setAllowedCidrs(resolveCidrs(command.allowedCidrs()));
    }

    /** 校验永久有效与指定过期时间的互斥关系。 */
    private Instant resolveExpiresAt(Boolean neverExpires, Instant expiresAt) {
        if (Boolean.TRUE.equals(neverExpires)) {
            if (expiresAt != null) throw new BusinessException("永久有效时不能设置过期时间");
            return null;
        }
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) throw new BusinessException("过期时间必须晚于当前时间");
        return expiresAt;
    }

    /** 校验限流周期和调用次数，兼容历史每分钟字段。 */
    private RateLimitConfiguration resolveRateLimit(ApiKeyCommand command) {
        ApiKeyRateLimitType type = command.rateLimitType();
        Integer count = command.rateLimitCount();
        if (type == null) {
            type = ApiKeyRateLimitType.MINUTE;
            count = command.rateLimitPerMinute() == null ? 60 : command.rateLimitPerMinute();
        }
        if (!type.isLimited()) {
            if (count != null) throw new BusinessException("无限制模式不能设置调用次数");
            return new RateLimitConfiguration(type, null);
        }
        if (count == null || count < 1 || count > 100000) {
            throw new BusinessException("调用次数必须在 1 到 100000 之间");
        }
        return new RateLimitConfiguration(type, count);
    }

    /** 校验接口代码全部来自代码开放目录。 */
    private Set<String> resolveEndpointCodes(Set<String> values) {
        if (values == null || values.isEmpty()) throw new BusinessException("请至少选择一个开放 API");
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String value : values) {
            String code = requireText(value, "API 接口代码不能为空", 120);
            if (!endpointCatalog.contains(code)) throw new BusinessException("API 接口不存在或不允许开放: " + code);
            codes.add(code);
        }
        return codes;
    }

    /** 校验并规范化 IP 白名单规则。 */
    private Set<String> resolveCidrs(Set<String> values) {
        LinkedHashSet<String> rules = new LinkedHashSet<>();
        if (values != null) values.stream().filter(value -> value != null && !value.isBlank())
            .map(cidrMatcher::normalize).forEach(rules::add);
        return rules;
    }

    /** 查询未吊销 API Key。 */
    private ApiKeyCredential requireCredential(Long id) {
        return repository.findByIdAndRevokedAtIsNull(id).orElseThrow(() -> BusinessException.notFound("API Key 不存在"));
    }

    /** 校验必填文本和最大长度。 */
    private String requireText(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) throw new BusinessException(message);
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new BusinessException(message + "，长度不能超过 " + maxLength);
        return normalized;
    }

    /** 将实体转换为不包含 Secret 的页面模型。 */
    private ApiKeyView toView(ApiKeyCredential credential) {
        UserAccount owner = credential.getOwner();
        return new ApiKeyView(credential.getId(), credential.getName(), secretService.displayPrefix(credential.getKeyId()),
            owner.getId(), owner.getUsername(), owner.getDisplayName(), credential.getEnabled(), credential.getExpiresAt() == null,
            credential.getExpiresAt(), credential.getRateLimitType(), credential.getRateLimitCount(),
            credential.getRateLimitType() == ApiKeyRateLimitType.MINUTE ? credential.getRateLimitCount() : null,
            credential.getEndpointCodes(), credential.getAllowedCidrs(),
            credential.getLastUsedAt(), credential.getLastUsedIp(), credential.getCreatedAt(), credential.getUpdatedAt());
    }

    public record ApiKeyCommand(String name, Long ownerUserId, Boolean enabled, Boolean neverExpires, Instant expiresAt,
                                ApiKeyRateLimitType rateLimitType, Integer rateLimitCount, Integer rateLimitPerMinute,
                                Set<String> endpointCodes, Set<String> allowedCidrs) {}
    public record ApiKeyView(Long id, String name, String keyPrefix, Long ownerUserId, String ownerUsername,
                             String ownerDisplayName, Boolean enabled, Boolean neverExpires, Instant expiresAt,
                             ApiKeyRateLimitType rateLimitType, Integer rateLimitCount, Integer rateLimitPerMinute,
                             Set<String> endpointCodes, Set<String> allowedCidrs,
                             Instant lastUsedAt, String lastUsedIp, Instant createdAt, Instant updatedAt) {}
    public record CreatedApiKey(ApiKeyView item, String apiKey) {}
    public record RotatedApiKey(ApiKeyView item, String apiKey) {}
    public record OwnerView(Long id, String username, String displayName) {}
    private record RateLimitConfiguration(ApiKeyRateLimitType type, Integer count) {}
}
