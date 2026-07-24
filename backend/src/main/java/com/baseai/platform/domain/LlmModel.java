package com.baseai.platform.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Entity
@Table(name = "sys_llm_model", uniqueConstraints = @UniqueConstraint(name = "uk_llm_model_code", columnNames = "code"))
public class LlmModel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 80) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false) private Long providerId;
    @Column(nullable = false, length = 160) private String modelName;
    /**
     * 模型支持的类型编码集合，使用逗号分隔存放在历史 model_type 列中。
     * 读取时兼容旧版 TEXT、VISION、BOTH，避免升级后已有模型失效。
     */
    @Column(name = "model_type", nullable = false, length = 1000) private String supportedModelTypes = "text_model";
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
    /** 返回规范化且去重后的模型类型编码集合。 */
    @JsonProperty("supportedModelTypes")
    public List<String> getSupportedModelTypes() { return normalizeModelTypes(supportedModelTypes==null?List.of():List.of(supportedModelTypes)); }
    /** 保存规范化且去重后的模型类型编码集合。 */
    @JsonProperty("supportedModelTypes")
    public void setSupportedModelTypes(Collection<String> values) { supportedModelTypes=String.join(",",normalizeModelTypes(values)); }
    /**
     * 保留旧 modelType JSON 字段，兼容尚未升级的调用方；新代码统一使用 supportedModelTypes。
     */
    @JsonProperty("modelType")
    public String getModelType() {
        List<String> types=getSupportedModelTypes();
        if(types.equals(List.of("text_model")))return "TEXT";
        if(types.equals(List.of("vision_model")))return "VISION";
        if(new LinkedHashSet<>(types).equals(new LinkedHashSet<>(List.of("text_model","vision_model"))))return "BOTH";
        return String.join(",",types);
    }
    /** 接收旧版单值或新版逗号分隔类型编码。 */
    @JsonProperty("modelType")
    public void setModelType(String value) { setSupportedModelTypes(value==null?List.of():List.of(value)); }
    public String getCapabilityLevel() { return capabilityLevel; } public void setCapabilityLevel(String value) { capabilityLevel=value; }
    public String getThinkingLevels() { return thinkingLevels; } public void setThinkingLevels(String value) { thinkingLevels=value; }
    public String getHealthStatus() { return healthStatus; } public void setHealthStatus(String value) { healthStatus=value; }
    public Long getLastCheckDurationMs() { return lastCheckDurationMs; } public void setLastCheckDurationMs(Long value) { lastCheckDurationMs=value; }
    public String getLastCheckError() { return lastCheckError; } public void setLastCheckError(String value) { lastCheckError=value; }
    public java.time.LocalDateTime getLastCheckedAt() { return lastCheckedAt; } public void setLastCheckedAt(java.time.LocalDateTime value) { lastCheckedAt=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }

    /** 将旧版枚举和任意未来类型统一为小写下划线编码。 */
    public static List<String> normalizeModelTypes(Collection<String> values) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(values!=null)for(String value:values){
            if(value==null)continue;
            for(String item:value.split(",")){
                String normalized=item.trim().toLowerCase(Locale.ROOT);
                if(normalized.isBlank())continue;
                if("both".equals(normalized)){result.add("text_model");result.add("vision_model");continue;}
                if("text".equals(normalized))normalized="text_model";
                if("vision".equals(normalized))normalized="vision_model";
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }
}
