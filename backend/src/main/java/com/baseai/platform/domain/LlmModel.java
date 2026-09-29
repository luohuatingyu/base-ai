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
    /** 自定义接入点使用显式能力画像；空值按官方模型标识识别。 */
    @Column(length = 80) private String generationProfile = "";
    /** 模型支持的类型编码集合，使用逗号分隔存放在 model_type 列中。 */
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
    /** 读取媒体生成模型的能力画像。 */
    public String getGenerationProfile() { return generationProfile; }
    /** 保存已校验的媒体生成能力画像。 */
    public void setGenerationProfile(String value) { generationProfile=value; }
    /** 返回规范化且去重后的模型类型编码集合。 */
    @JsonProperty("supportedModelTypes")
    public List<String> getSupportedModelTypes() { return normalizeModelTypes(supportedModelTypes==null?List.of():List.of(supportedModelTypes)); }
    /** 保存规范化且去重后的模型类型编码集合。 */
    @JsonProperty("supportedModelTypes")
    public void setSupportedModelTypes(Collection<String> values) { supportedModelTypes=String.join(",",normalizeModelTypes(values)); }
    public String getCapabilityLevel() { return capabilityLevel; } public void setCapabilityLevel(String value) { capabilityLevel=value; }
    public String getThinkingLevels() { return thinkingLevels; } public void setThinkingLevels(String value) { thinkingLevels=value; }
    public String getHealthStatus() { return healthStatus; } public void setHealthStatus(String value) { healthStatus=value; }
    public Long getLastCheckDurationMs() { return lastCheckDurationMs; } public void setLastCheckDurationMs(Long value) { lastCheckDurationMs=value; }
    public String getLastCheckError() { return lastCheckError; } public void setLastCheckError(String value) { lastCheckError=value; }
    public java.time.LocalDateTime getLastCheckedAt() { return lastCheckedAt; } public void setLastCheckedAt(java.time.LocalDateTime value) { lastCheckedAt=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }

    /** 图片与视频生成是独立能力，不属于聊天或视觉理解协议。 */
    public static boolean isGenerationType(String type) {
        return type != null && ("image_model".equalsIgnoreCase(type.trim()) || "video_model".equalsIgnoreCase(type.trim()));
    }

    /** 将当前模型类型编码统一为小写并去重。 */
    public static List<String> normalizeModelTypes(Collection<String> values) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(values!=null)for(String value:values){
            if(value==null)continue;
            for(String item:value.split(",")){
                String normalized=item.trim().toLowerCase(Locale.ROOT);
                if(normalized.isBlank())continue;
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }
}
