package com.baseai.platform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "sys_llm_model", uniqueConstraints = @UniqueConstraint(name = "uk_llm_model_code", columnNames = "code"))
public class LlmModel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 80) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false) private Long providerId;
    @Column(nullable = false, length = 160) private String modelName;
    @Column(nullable = false, length = 24) private String modelType = "TEXT";
    @Column(nullable = false, length = 24) private String capabilityLevel = "MIDDLE";
    /** 标准思考等级到供应商实际值的映射，格式：EXTRA_HIGH=xhigh,LOW=low。 */
    @Column(length = 1000) private String thinkingLevels = "";
    /** 最近一次连通性检查状态：UNKNOWN、HEALTHY、WARNING、SLOW、FAILED。 */
    @Column(nullable = false, length = 16) private String healthStatus = "UNKNOWN";
    @Column private Long lastCheckDurationMs;
    @Column(length = 1000) private String lastCheckError = "";
    @Column private java.time.LocalDateTime lastCheckedAt;
    @Column(nullable = false) private Boolean enabled = true;
    public Long getId() { return id; } public String getCode() { return code; } public void setCode(String value) { code=value; }
    public String getName() { return name; } public void setName(String value) { name=value; }
    public Long getProviderId() { return providerId; } public void setProviderId(Long value) { providerId=value; }
    public String getModelName() { return modelName; } public void setModelName(String value) { modelName=value; }
    public String getModelType() { return modelType; } public void setModelType(String value) { modelType=value; }
    public String getCapabilityLevel() { return capabilityLevel; } public void setCapabilityLevel(String value) { capabilityLevel=value; }
    public String getThinkingLevels() { return thinkingLevels; } public void setThinkingLevels(String value) { thinkingLevels=value; }
    public String getHealthStatus() { return healthStatus; } public void setHealthStatus(String value) { healthStatus=value; }
    public Long getLastCheckDurationMs() { return lastCheckDurationMs; } public void setLastCheckDurationMs(Long value) { lastCheckDurationMs=value; }
    public String getLastCheckError() { return lastCheckError; } public void setLastCheckError(String value) { lastCheckError=value; }
    public java.time.LocalDateTime getLastCheckedAt() { return lastCheckedAt; } public void setLastCheckedAt(java.time.LocalDateTime value) { lastCheckedAt=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }
}
