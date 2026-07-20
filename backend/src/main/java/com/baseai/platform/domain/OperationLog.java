package com.baseai.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sys_operation_log", indexes = @Index(name = "idx_operation_log_time", columnList = "operatedAt"))
public class OperationLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    @Column(length = 64) private String username;
    @Column(nullable = false, length = 16) private String method;
    @Column(nullable = false, length = 255) private String path;
    @Column(length = 64) private String controller;
    @Column(length = 64) private String action;
    @Column(columnDefinition = "MEDIUMTEXT") private String requestData;
    @Column(length = 64) private String ipAddress;
    private Long durationMs;
    @Column(nullable = false) private Boolean success;
    @Column(length = 1000) private String errorMessage;
    @Column(nullable = false) private Instant operatedAt;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getController() { return controller; }
    public void setController(String controller) { this.controller = controller; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getRequestData() { return requestData; }
    public void setRequestData(String requestData) { this.requestData = requestData; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getOperatedAt() { return operatedAt; }
    public void setOperatedAt(Instant operatedAt) { this.operatedAt = operatedAt; }
}
