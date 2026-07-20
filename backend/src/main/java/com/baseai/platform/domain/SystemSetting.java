package com.baseai.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sys_setting", uniqueConstraints = @UniqueConstraint(name = "uk_sys_setting_key", columnNames = "configKey"))
public class SystemSetting {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 64) private String groupCode;
    @Column(nullable = false, length = 120) private String configKey;
    @Column(nullable = false, length = 120) private String name;
    @Column(columnDefinition = "MEDIUMTEXT") private String configValue;
    @Column(nullable = false) private Boolean sensitive = false;
    @Column(nullable = false) private Boolean enabled = true;
    @Column(nullable = false) private Instant updatedAt;
    @PrePersist @PreUpdate public void updateTime() { updatedAt = Instant.now(); }
    public Long getId() { return id; } public String getGroupCode() { return groupCode; } public void setGroupCode(String value) { groupCode=value; }
    public String getConfigKey() { return configKey; } public void setConfigKey(String value) { configKey=value; }
    public String getName() { return name; } public void setName(String value) { name=value; }
    public String getConfigValue() { return configValue; } public void setConfigValue(String value) { configValue=value; }
    public Boolean getSensitive() { return sensitive; } public void setSensitive(Boolean value) { sensitive=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }
    public Instant getUpdatedAt() { return updatedAt; }
}
