package com.baseai.platform.modeltype;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Gemini Image、Gemini Omni Flash 与 Veo 的 API-Key Agent 策略。 */
public final class GeminiMediaGenerationAgentStrategy implements MediaGenerationAgentStrategy {
    /** 返回 Gemini 策略编码。 */
    @Override public String id() { return "GEMINI"; }

    /** 按供应商编码、模型名或显式画像识别 Gemini。 */
    @Override public boolean supports(String providerCode, String model, String profile) {
        String value = normalize(providerCode) + " " + normalize(model) + " " + normalize(profile);
        return value.contains("gemini") || value.contains("google") || value.contains("veo") || value.contains("imagen");
    }

    /** Gemini API 使用模型方法路径；视频状态由 operations 资源查询。 */
    @Override public String generationPath(String modelType, String model) {
        return "/models/" + model + ":generateContent";
    }

    /** 视频长任务通过 Google operations 查询，图片响应为同步内容。 */
    @Override public String statusPath(String modelType, String model, String remoteId) {
        return "video_model".equals(modelType) ? "/operations/" + remoteId : null;
    }

    /** 返回 Gemini 创建接口路径。 */
    @Override public String operationPath(String modelType, String model) {
        return generationPath(modelType, model);
    }

    /** 构造 Gemini generateContent 所需的 contents、parts 和生成配置。 */
    @Override public Map<String, Object> request(String modelType, String model, String profile, String prompt,
                                                   List<Map<String, Object>> inputs, Map<String, Object> options) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (prompt != null && !prompt.isBlank()) parts.add(Map.of("text", prompt));
        if (inputs != null) for (Map<String, Object> input : inputs) {
            String data = text(input, "data");
            String encoded = data.startsWith("data:") && data.contains(",") ? data.substring(data.indexOf(',') + 1) : data;
            if ("TEXT".equalsIgnoreCase(text(input, "kind"))) parts.add(Map.of("text", decodeText(encoded)));
            else if (SetLike.MEDIA.contains(text(input, "kind").toUpperCase(Locale.ROOT))) {
                parts.add(Map.of("inline_data", Map.of("mime_type", text(input, "content_type"), "data", encoded)));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("role", "user", "parts", parts)));
        if (options != null && !options.isEmpty()) body.put("generationConfig", new LinkedHashMap<>(options));
        return body;
    }

    /** 返回 Gemini 图片和视频的最小能力目录。 */
    @Override public Map<String, Object> capabilities(String modelType, String model, String profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile == null || profile.isBlank() ? model : profile);
        result.put("type", modelType);
        result.put("label", model);
        result.put("modes", "video_model".equals(modelType)
            ? List.of("TEXT_TO_VIDEO", "IMAGE_TO_VIDEO", "VIDEO_TO_VIDEO")
            : List.of("TEXT_TO_IMAGE", "IMAGE_TO_IMAGE"));
        result.put("imageLimit", 4);
        result.put("videoLimit", 1);
        result.put("audioLimit", 1);
        result.put("totalLimit", 4);
        result.put("features", Map.of("apiKey", true, "multimodal", true, "async", "video_model".equals(modelType)));
        result.put("options", Map.of("aspect_ratio", Map.of("type", "string"),
            "resolution", Map.of("type", "string"), "duration", Map.of("type", "integer"),
            "generate_audio", Map.of("type", "boolean")));
        return result;
    }

    /** 统一读取输入字段，避免策略层依赖平台记录类型。 */
    private String text(Map<String, Object> value, String key) {
        return value == null || value.get(key) == null ? "" : String.valueOf(value.get(key));
    }

    /** 将文本 Data URL 还原为提示词，远程文本保持原文。 */
    private String decodeText(String value) {
        try { return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ignored) { return value; }
    }

    /** 规范化供应商、模型和画像名称。 */
    private String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** 策略层允许转成 inline_data 的媒体输入类型。 */
    private static final class SetLike {
        private static final java.util.Set<String> MEDIA = java.util.Set.of("IMAGE", "VIDEO", "AUDIO");
    }
}
