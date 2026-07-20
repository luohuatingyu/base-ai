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
    @Column(nullable = false) private Boolean enabled = true;
    public Long getId() { return id; } public String getCode() { return code; } public void setCode(String value) { code=value; }
    public String getName() { return name; } public void setName(String value) { name=value; }
    public Long getProviderId() { return providerId; } public void setProviderId(Long value) { providerId=value; }
    public String getModelName() { return modelName; } public void setModelName(String value) { modelName=value; }
    public String getModelType() { return modelType; } public void setModelType(String value) { modelType=value; }
    public String getCapabilityLevel() { return capabilityLevel; } public void setCapabilityLevel(String value) { capabilityLevel=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }
}
