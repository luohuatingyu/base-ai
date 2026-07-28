package com.baseai.platform.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "sys_api_key", indexes = {
    @Index(name = "idx_api_key_owner", columnList = "owner_user_id"),
    @Index(name = "idx_api_key_enabled", columnList = "enabled,revoked_at")
})
public class ApiKeyCredential {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_id", nullable = false, unique = true, length = 32)
    private String keyId;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "secret_encrypted", columnDefinition = "TEXT")
    private String secretEncrypted;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private UserAccount owner;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private Integer rateLimitPerMinute = 60;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_limit_type", length = 20)
    private ApiKeyRateLimitType rateLimitType;

    @Column(name = "rate_limit_count")
    private Integer rateLimitCount;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sys_api_key_endpoint", joinColumns = @JoinColumn(name = "api_key_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_api_key_endpoint", columnNames = {"api_key_id", "endpoint_code"}))
    @Column(name = "endpoint_code", nullable = false, length = 120)
    private Set<String> endpointCodes = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sys_api_key_ip_rule", joinColumns = @JoinColumn(name = "api_key_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_api_key_ip_rule", columnNames = {"api_key_id", "cidr"}))
    @Column(name = "cidr", nullable = false, length = 64)
    private Set<String> allowedCidrs = new LinkedHashSet<>();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_used_ip", length = 64)
    private String lastUsedIp;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** 创建记录时初始化审计时间。 */
    @PrePersist
    public void initializeAuditTime() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** 更新记录时刷新修改时间。 */
    @PreUpdate
    public void refreshAuditTime() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getSecretHash() { return secretHash; }
    public void setSecretHash(String secretHash) { this.secretHash = secretHash; }
    /** 读取仅用于管理员查看的 API Key 加密副本。 */
    public String getSecretEncrypted() { return secretEncrypted; }
    /** 保存仅用于管理员查看的 API Key 加密副本。 */
    public void setSecretEncrypted(String secretEncrypted) { this.secretEncrypted = secretEncrypted; }
    public UserAccount getOwner() { return owner; }
    public void setOwner(UserAccount owner) { this.owner = owner; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Integer getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(Integer rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public ApiKeyRateLimitType getRateLimitType() {
        return rateLimitType == null ? ApiKeyRateLimitType.MINUTE : rateLimitType;
    }
    public void setRateLimitType(ApiKeyRateLimitType rateLimitType) { this.rateLimitType = rateLimitType; }
    public Integer getRateLimitCount() {
        return rateLimitType == null ? rateLimitPerMinute : rateLimitCount;
    }
    public void setRateLimitCount(Integer rateLimitCount) { this.rateLimitCount = rateLimitCount; }
    /** 判断是否仍使用历史每分钟限流字段。 */
    public boolean hasLegacyRateLimitConfiguration() { return rateLimitType == null; }
    public Set<String> getEndpointCodes() { return endpointCodes; }
    public void setEndpointCodes(Set<String> endpointCodes) { this.endpointCodes = endpointCodes; }
    public Set<String> getAllowedCidrs() { return allowedCidrs; }
    public void setAllowedCidrs(Set<String> allowedCidrs) { this.allowedCidrs = allowedCidrs; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getLastUsedIp() { return lastUsedIp; }
    public void setLastUsedIp(String lastUsedIp) { this.lastUsedIp = lastUsedIp; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
