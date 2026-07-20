package com.baseai.platform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "sys_dictionary_data", uniqueConstraints = @UniqueConstraint(name = "uk_dictionary_data", columnNames = {"typeCode", "dictValue"}))
public class DictionaryData {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 80) private String typeCode;
    @Column(nullable = false, length = 120) private String label;
    @Column(nullable = false, length = 120) private String dictValue;
    @Column(nullable = false) private Integer sortOrder = 0;
    @Column(nullable = false) private Boolean enabled = true;
    public Long getId() { return id; } public String getTypeCode() { return typeCode; } public void setTypeCode(String value) { typeCode=value; }
    public String getLabel() { return label; } public void setLabel(String value) { label=value; }
    public String getDictValue() { return dictValue; } public void setDictValue(String value) { dictValue=value; }
    public Integer getSortOrder() { return sortOrder; } public void setSortOrder(Integer value) { sortOrder=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }
}
