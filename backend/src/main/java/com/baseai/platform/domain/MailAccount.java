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

/** 可由邮件路由选择的 SMTP 邮箱账户。 */
@Entity
@Table(name = "sys_mail_account",
    uniqueConstraints = @UniqueConstraint(name = "uk_mail_account_code", columnNames = "code"))
public class MailAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64)
    private String code;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 255)
    private String host;
    @Column(nullable = false)
    private Integer port;
    @Column(nullable = false, length = 255)
    private String username;
    @Column(nullable = false, length = 255)
    private String fromAddress;
    @Column(nullable = false, length = 16)
    private String tlsMode;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String passwordEncrypted;
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
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getTlsMode() { return tlsMode; }
    public void setTlsMode(String tlsMode) { this.tlsMode = tlsMode; }
    public String getPasswordEncrypted() { return passwordEncrypted; }
    public void setPasswordEncrypted(String passwordEncrypted) { this.passwordEncrypted = passwordEncrypted; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
