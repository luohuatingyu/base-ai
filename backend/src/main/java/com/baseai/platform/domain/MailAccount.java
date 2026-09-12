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
import java.util.LinkedHashSet;
import java.util.Set;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;

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
    private Boolean imapEnabled = false;
    private String imapHost;
    private Integer imapPort;
    private String imapUsername;
    private String imapTlsMode;
    @Column(columnDefinition = "TEXT")
    private String imapPasswordEncrypted;
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sys_mail_account_role", joinColumns = @JoinColumn(name = "account_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();
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
    /** 返回收件启用状态。 */
    public Boolean getImapEnabled() { return imapEnabled; }
    /** 设置收件启用状态。 */
    public void setImapEnabled(Boolean value) { imapEnabled = value; }
    /** 返回收件服务器。 */
    public String getImapHost() { return imapHost; }
    /** 设置收件服务器。 */
    public void setImapHost(String value) { imapHost = value; }
    /** 返回收件端口。 */
    public Integer getImapPort() { return imapPort; }
    /** 设置收件端口。 */
    public void setImapPort(Integer value) { imapPort = value; }
    /** 返回收件账号。 */
    public String getImapUsername() { return imapUsername; }
    /** 设置收件账号。 */
    public void setImapUsername(String value) { imapUsername = value; }
    /** 返回收件加密模式。 */
    public String getImapTlsMode() { return imapTlsMode; }
    /** 设置收件加密模式。 */
    public void setImapTlsMode(String value) { imapTlsMode = value; }
    /** 返回收件密码密文。 */
    public String getImapPasswordEncrypted() { return imapPasswordEncrypted; }
    /** 设置收件密码密文。 */
    public void setImapPasswordEncrypted(String value) { imapPasswordEncrypted = value; }
    /** 返回授权角色集合。 */
    public Set<Role> getRoles() { return roles; }
    /** 替换授权角色集合。 */
    public void setRoles(Set<Role> value) { roles = value; }
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
