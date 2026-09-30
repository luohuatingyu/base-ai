package com.baseai.platform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "sys_llm_provider", uniqueConstraints = @UniqueConstraint(name = "uk_llm_provider_code", columnNames = "code"))
public class LlmProvider {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 64) private String code;
    @Column(nullable = false, length = 120) private String name;
    /** 供应商绑定的协议策略；空值仅用于兼容迁移前的历史配置。 */
    @Column(name = "strategy_code", length = 32) private String strategyCode;
    @Column(length = 500) private String baseUrl;
    @Column(length = 500) private String imageUrl;
    @Column(length = 500) private String videoUrl;
    /** 图片是否跟随 Base URL。 */
    @Column(nullable = false) private Boolean imageSameAsBase = true;
    /** 视频是否跟随图片 URL。 */
    @Column(nullable = false) private Boolean videoSameAsImage = true;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT") private String apiKeysEncrypted;
    @Column(nullable = false) private Integer concurrencyLimit = 4;
    @Column(nullable = false, length = 24) private String concurrencyLevel = "PROVIDER";
    @Column(nullable = false) private Integer timeoutSeconds = 60;
    /** 思考等级的供应商请求字段，默认兼容 OpenAI reasoning_effort。 */
    @Column(nullable = false, length = 64) private String thinkingParameter = "reasoning_effort";
    @Column(nullable = false) private Boolean enabled = true;
    public Long getId() { return id; } public String getCode() { return code; } public void setCode(String value) { code=value; }
    public String getName() { return name; } public void setName(String value) { name=value; }
    /** 返回供应商绑定的稳定策略编码。 */
    public String getStrategyCode() { return strategyCode; }
    /** 保存供应商绑定的稳定策略编码。 */
    public void setStrategyCode(String value) { strategyCode=value; }
    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String value) { baseUrl=value; }
    /** 读取和更新图片 URL。 */
    public String getImageUrl() { return imageUrl; } public void setImageUrl(String value) { imageUrl=value; }
    /** 读取和更新视频 URL。 */
    public String getVideoUrl() { return videoUrl; } public void setVideoUrl(String value) { videoUrl=value; }
    /** 读取和更新图片同步标记。 */
    public Boolean getImageSameAsBase() { return imageSameAsBase; } public void setImageSameAsBase(Boolean value) { imageSameAsBase=value; }
    /** 读取和更新视频同步标记。 */
    public Boolean getVideoSameAsImage() { return videoSameAsImage; } public void setVideoSameAsImage(Boolean value) { videoSameAsImage=value; }
    /** 返回图片实际调用地址。 */
    public String effectiveImageUrl() { return Boolean.TRUE.equals(imageSameAsBase) ? baseUrl : imageUrl; }
    /** 返回视频实际调用地址。 */
    public String effectiveVideoUrl() { return Boolean.TRUE.equals(videoSameAsImage) ? effectiveImageUrl() : videoUrl; }
    public String getApiKeysEncrypted() { return apiKeysEncrypted; } public void setApiKeysEncrypted(String value) { apiKeysEncrypted=value; }
    public Integer getConcurrencyLimit() { return concurrencyLimit; } public void setConcurrencyLimit(Integer value) { concurrencyLimit=value; }
    public String getConcurrencyLevel() { return concurrencyLevel; } public void setConcurrencyLevel(String value) { concurrencyLevel=value; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; } public void setTimeoutSeconds(Integer value) { timeoutSeconds=value; }
    public String getThinkingParameter() { return thinkingParameter; } public void setThinkingParameter(String value) { thinkingParameter=value; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean value) { enabled=value; }
}
