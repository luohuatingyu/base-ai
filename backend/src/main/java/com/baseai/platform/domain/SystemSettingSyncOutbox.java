package com.baseai.platform.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** 记录系统配置缓存同步任务，支持进程异常后的 Redis 对账恢复。 */
@Entity
@Table(name = "sys_setting_sync_outbox",
    indexes = @Index(name = "idx_sys_setting_sync_outbox_pending", columnList = "processedAt,createdAt"))
public class SystemSettingSyncOutbox {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 120) private String configKey;
    @Column(nullable = false, length = 16) private String operation;
    @Column(nullable = false) private Integer attempts = 0;
    @Column(nullable = false) private Instant createdAt;
    private Instant processedAt;
    @Column(length = 500) private String lastError;

    @PrePersist
    public void initialize() {
        if (createdAt == null) createdAt = Instant.now();
        if (attempts == null) attempts = 0;
    }

    public Long getId() { return id; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String value) { configKey = value; }
    public String getOperation() { return operation; }
    public void setOperation(String value) { operation = value; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer value) { attempts = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant value) { processedAt = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
}
