package com.baseai.platform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "sys_llm_route", uniqueConstraints = @UniqueConstraint(name = "uk_llm_route_feature", columnNames = "featureCode"))
public class LlmRoute {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 100) private String featureCode;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 1000) private String candidateModelIds;
    @Column(nullable = false) private Boolean enableThinking = false;
    @Column(nullable = false) private Boolean enabled = true;
    public Long getId() { return id; } public String getFeatureCode() { return featureCode; } public void setFeatureCode(String value) { featureCode=value; }
    public String getName() { return name; } public void setName(String value) { name=value; }
    public String getCandidateModelIds() { return candidateModelIds; } public void setCandidateModelIds(String value) { candidateModelIds=value; }
    public Boolean getEnableThinking() { return enableThinking; } public void setEnableThinking(Boolean value) { enableThinking=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }
}
