package com.baseai.platform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "sys_llm_provider", uniqueConstraints = @UniqueConstraint(name = "uk_llm_provider_code", columnNames = "code"))
public class LlmProvider {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 64) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 500) private String baseUrl;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT") private String apiKeysEncrypted;
    @Column(nullable = false) private Integer concurrencyLimit = 4;
    @Column(nullable = false, length = 24) private String concurrencyLevel = "PROVIDER";
    @Column(nullable = false) private Integer timeoutSeconds = 60;
    @Column(nullable = false) private Boolean enabled = true;
    public Long getId() { return id; } public String getCode() { return code; } public void setCode(String value) { code=value; }
    public String getName() { return name; } public void setName(String value) { name=value; }
    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String value) { baseUrl=value; }
    public String getApiKeysEncrypted() { return apiKeysEncrypted; } public void setApiKeysEncrypted(String value) { apiKeysEncrypted=value; }
    public Integer getConcurrencyLimit() { return concurrencyLimit; } public void setConcurrencyLimit(Integer value) { concurrencyLimit=value; }
    public String getConcurrencyLevel() { return concurrencyLevel; } public void setConcurrencyLevel(String value) { concurrencyLevel=value; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; } public void setTimeoutSeconds(Integer value) { timeoutSeconds=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }
}
