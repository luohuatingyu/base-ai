package com.baseai.platform.modeltype;

import java.util.List;
import java.util.Map;

/** 火山方舟 Seedream/Seedance Agent 策略。 */
public final class ArkMediaGenerationAgentStrategy implements MediaGenerationAgentStrategy {
    /** 返回方舟策略编码。 */
    @Override public String id() { return "ARK"; }

    /** 识别官方模型名和管理员配置的方舟能力画像。 */
    @Override public boolean supports(String providerCode, String model, String profile) {
        return ArkMediaProtocol.supports(profile) || ArkMediaProtocol.supports(model)
            || ArkMediaProtocol.supports(providerCode);
    }

    /** 返回方舟图片或视频创建路径。 */
    @Override public String generationPath(String modelType, String model) {
        return "video_model".equals(modelType) ? "/contents/generations/tasks" : "/images/generations";
    }

    /** 返回方舟视频任务查询路径。 */
    @Override public String statusPath(String modelType, String model, String remoteId) {
        return "video_model".equals(modelType) ? "/contents/generations/tasks/" + remoteId : null;
    }

    /** 返回方舟创建接口路径。 */
    @Override public String operationPath(String modelType, String model) {
        return generationPath(modelType, model);
    }

    /** 调用现有方舟协议映射，保持历史字段和安全校验不变。 */
    @Override public Map<String, Object> request(String modelType, String model, String profile, String prompt,
                                                   List<Map<String, Object>> inputs, Map<String, Object> options) {
        return ArkMediaProtocol.request(modelType, model, profile, prompt, inputs, options);
    }

    /** 返回方舟模型的细粒度能力目录。 */
    @Override public Map<String, Object> capabilities(String modelType, String model, String profile) {
        Map<String, Object> detected = ArkMediaProtocol.capabilities(profile == null || profile.isBlank() ? model : profile);
        if (modelType.equals(detected.get("type"))) return detected;
        String fallback = "image_model".equals(modelType) ? "seedream-4.5" : "seedance-2.0";
        Map<String, Object> result = new java.util.LinkedHashMap<>(ArkMediaProtocol.capabilities(fallback));
        result.put("profile", profile == null || profile.isBlank() ? model : profile);
        result.put("type", modelType);
        return result;
    }

    /** 方舟能力允许使用已经落盘的公共素材地址。 */
    @Override public boolean supportsRemoteInputs() { return true; }
}
