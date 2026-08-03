package com.baseai.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/** 将具体业务编码映射到邮箱账户和收件人列表。 */
@Entity
@Table(name = "sys_mail_route",
    uniqueConstraints = @UniqueConstraint(name = "uk_mail_route_business", columnNames = "businessCode"))
public class MailRoute {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64)
    private String businessCode;
    @Column(nullable = false, length = 120)
    private String name;
    @Column
    private Long accountId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String toAddresses;
    @Column(columnDefinition = "TEXT")
    private String ccAddresses;
    @Column(nullable = false)
    private Boolean enabled = true;
    @Column(nullable = false)
    private LocalDateTime createdAt;
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** 新建时补充审计时间。 */
    @PrePersist
    void beforeInsert() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    /** 更新时刷新审计时间。 */
    @PreUpdate
    void beforeUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getBusinessCode() { return businessCode; }
    public void setBusinessCode(String businessCode) { this.businessCode = businessCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getToAddresses() { return toAddresses; }
    public void setToAddresses(String toAddresses) { this.toAddresses = toAddresses; }
    public String getCcAddresses() { return ccAddresses; }
    public void setCcAddresses(String ccAddresses) { this.ccAddresses = ccAddresses; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
