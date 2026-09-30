package com.baseai.platform.modeltype;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** OpenAI GPT Image 与 Sora 的 API-Key Agent 策略。 */
public final class OpenAiMediaGenerationAgentStrategy implements MediaGenerationAgentStrategy {
    /** 返回 OpenAI 策略编码。 */
    @Override public String id() { return "OPENAI"; }

    /** 按供应商编码、模型名或显式画像识别 OpenAI。 */
    @Override public boolean supports(String providerCode, String model, String profile) {
        String value = normalize(providerCode) + " " + normalize(model) + " " + normalize(profile);
        return value.contains("openai") || value.contains("gpt-image") || value.contains("dall-e") || value.contains("sora");
    }

    /** 返回 OpenAI 图片或视频创建路径。 */
    @Override public String generationPath(String modelType, String model) {
        return "video_model".equals(modelType) ? "/videos" : "/images/generations";
    }

    /** Sora 返回异步视频任务，图片生成保持同步。 */
    @Override public String statusPath(String modelType, String model, String remoteId) {
        return "video_model".equals(modelType) ? "/videos/" + remoteId : null;
    }

    /** 返回 OpenAI 视频任务创建路径或图片创建路径。 */
    @Override public String operationPath(String modelType, String model) {
        return generationPath(modelType, model);
    }

    /** 构造 OpenAI 图片和视频的统一 JSON 表达，便于兼容代理网关。 */
    @Override public Map<String, Object> request(String modelType, String model, String profile, String prompt,
                                                   List<Map<String, Object>> inputs, Map<String, Object> options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (prompt != null && !prompt.isBlank()) body.put("prompt", prompt);
        if (options != null) body.putAll(options);
        if ("video_model".equals(modelType)) {
            List<String> images = inputs == null ? List.of() : inputs.stream()
                .filter(item -> "IMAGE".equalsIgnoreCase(text(item, "kind")))
                .map(item -> text(item, "data")).toList();
            if (!images.isEmpty()) body.put("input_reference", images.get(0));
            if (body.containsKey("duration")) {
                body.put("seconds", body.remove("duration"));
            }
        } else {
            List<String> images = inputs == null ? List.of() : inputs.stream()
                .filter(item -> "IMAGE".equalsIgnoreCase(text(item, "kind")))
                .map(item -> text(item, "data")).toList();
            if (!images.isEmpty()) body.put("image", images.size() == 1 ? images.get(0) : images);
        }
        return body;
    }

    /** 返回 OpenAI 图片和视频可用的最小能力目录。 */
    @Override public Map<String, Object> capabilities(String modelType, String model, String profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile == null || profile.isBlank() ? model : profile);
        result.put("type", modelType);
        result.put("label", model);
        result.put("modes", "video_model".equals(modelType)
            ? List.of("TEXT_TO_VIDEO", "IMAGE_TO_VIDEO")
            : List.of("TEXT_TO_IMAGE", "IMAGE_TO_IMAGE"));
        result.put("imageLimit", 4);
        result.put("videoLimit", 1);
        result.put("audioLimit", 0);
        result.put("totalLimit", 4);
        result.put("features", Map.of("apiKey", true, "async", "video_model".equals(modelType)));
        result.put("options", "video_model".equals(modelType)
            ? Map.of("seconds", Map.of("type", "integer", "enum", List.of(4, 8, 12)),
                "size", Map.of("type", "string"))
            : Map.of("size", Map.of("type", "string"), "quality", Map.of("type", "string"),
                "background", Map.of("type", "string"), "output_format", Map.of("type", "string")));
        return result;
    }

    /** 统一读取输入字段，避免策略层直接依赖平台记录类型。 */
    private String text(Map<String, Object> value, String key) {
        return value == null || value.get(key) == null ? "" : String.valueOf(value.get(key));
    }

    /** 规范化供应商、模型和画像名称。 */
    private String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', '-'); }
}
