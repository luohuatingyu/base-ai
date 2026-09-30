package com.baseai.platform.modeltype;

import java.util.List;
import java.util.Map;

/** 保留旧通用 JSON 协议的兜底 Agent 策略。 */
public final class GenericMediaGenerationAgentStrategy implements MediaGenerationAgentStrategy {
    private final LlmModelTypeRegistry modelTypeRegistry;
    private final MediaGenerationProtocolRegistry protocolRegistry;

    /** 注入模型类型路径和历史协议注册表。 */
    public GenericMediaGenerationAgentStrategy(LlmModelTypeRegistry modelTypeRegistry,
                                               MediaGenerationProtocolRegistry protocolRegistry) {
        this.modelTypeRegistry = modelTypeRegistry;
        this.protocolRegistry = protocolRegistry;
    }

    /** 返回通用策略编码。 */
    @Override public String id() { return "GENERIC"; }

    /** 通用策略只作为其他专用策略均不匹配时的兜底。 */
    @Override public boolean supports(String providerCode, String model, String profile) { return true; }

    /** 返回历史模型类型创建路径。 */
    @Override public String generationPath(String modelType, String model) { return modelTypeRegistry.generationPath(modelType); }

    /** 返回历史通用视频查询路径。 */
    @Override public String statusPath(String modelType, String model, String remoteId) {
        return modelTypeRegistry.generationStatusPath(modelType, remoteId);
    }

    /** 返回历史通用创建路径。 */
    @Override public String operationPath(String modelType, String model) { return generationPath(modelType, model); }

    /** 复用原有模型类型协议映射，保证旧供应商请求不变。 */
    @Override public Map<String, Object> request(String modelType, String model, String profile, String prompt,
                                                  List<Map<String, Object>> inputs, Map<String, Object> options) {
        return protocolRegistry.request(modelType, model, prompt, "MULTIMODAL", inputs, options);
    }

    /** 通用供应商不声明额外能力，前端只显示基础模式。 */
    @Override public Map<String, Object> capabilities(String modelType, String model, String profile) {
        return Map.of("profile", profile == null || profile.isBlank() ? model : profile, "type", modelType,
            "label", model, "modes", "video_model".equals(modelType)
                ? List.of("TEXT_TO_VIDEO", "IMAGE_TO_VIDEO") : List.of("TEXT_TO_IMAGE", "IMAGE_TO_IMAGE"));
    }

    /** 通用协议没有供应商专用选项清理逻辑。 */
    @Override public boolean supportsMode(String modelType, String model, String profile, String mode) {
        return "MULTIMODAL".equals(mode) || MediaGenerationAgentStrategy.super.supportsMode(modelType, model, profile, mode);
    }
}
