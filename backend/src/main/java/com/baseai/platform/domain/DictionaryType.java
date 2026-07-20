package com.baseai.platform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "sys_dictionary_type", uniqueConstraints = @UniqueConstraint(name = "uk_dictionary_type_code", columnNames = "code"))
public class DictionaryType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 80) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(length = 500) private String description;
    @Column(nullable = false) private Boolean enabled = true;
    public Long getId() { return id; } public String getCode() { return code; } public void setCode(String value) { code=value; }
    public String getName() { return name; } public void setName(String value) { name=value; }
    public String getDescription() { return description; } public void setDescription(String value) { description=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }
}
