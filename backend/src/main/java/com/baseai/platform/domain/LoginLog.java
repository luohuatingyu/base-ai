package com.baseai.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sys_login_log", indexes = @Index(name = "idx_login_log_time", columnList = "loginAt"))
public class LoginLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 64) private String username;
    @Column(length = 64) private String ipAddress;
    @Column(length = 500) private String userAgent;
    @Column(nullable = false) private Boolean success;
    @Column(length = 500) private String message;
    @Column(nullable = false) private Instant loginAt;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getLoginAt() { return loginAt; }
    public void setLoginAt(Instant loginAt) { this.loginAt = loginAt; }
}
