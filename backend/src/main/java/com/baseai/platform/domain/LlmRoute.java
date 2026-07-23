package com.baseai.platform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "sys_llm_route", uniqueConstraints = @UniqueConstraint(name = "uk_llm_route_feature", columnNames = "featureCode"))
public class LlmRoute {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 100) private String featureCode;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 1000) private String candidateModelIds;
    /** 新路由的供应商池，按顺序保存供应商 ID；为空时兼容旧候选模型路由。 */
    @Column(length = 1000) private String providerIds = "";
    @Column(length = 24) private String capabilityLevel;
    @Column(length = 24) private String thinkingLevel;
    @Column(nullable = false) private Boolean enableThinking = false;
    @Column(nullable = false) private Boolean enabled = true;
    public Long getId() { return id; } public String getFeatureCode() { return featureCode; } public void setFeatureCode(String value) { featureCode=value; }
    public String getName() { return name; } public void setName(String value) { name=value; }
    public String getCandidateModelIds() { return candidateModelIds; } public void setCandidateModelIds(String value) { candidateModelIds=value; }
    public String getProviderIds() { return providerIds; } public void setProviderIds(String value) { providerIds=value; }
    public String getCapabilityLevel() { return capabilityLevel; } public void setCapabilityLevel(String value) { capabilityLevel=value; }
    public String getThinkingLevel() { return thinkingLevel; } public void setThinkingLevel(String value) { thinkingLevel=value; }
    public Boolean getEnableThinking() { return enableThinking; } public void setEnableThinking(Boolean value) { enableThinking=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }
}
