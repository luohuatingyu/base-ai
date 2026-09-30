package com.baseai.platform.modeltype;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** MiniMax 图片生成与海螺视频生成 Agent 策略。 */
public final class MiniMaxMediaGenerationAgentStrategy implements MediaGenerationAgentStrategy {
    /** 返回 MiniMax 策略编码。 */
    @Override public String id() { return "MINIMAX"; }
    /** 识别 MiniMax 供应商、海螺模型和显式能力画像。 */
    @Override public boolean supports(String providerCode, String model, String profile) { return contains(providerCode) || contains(model) || contains(profile); }
    /** 返回 MiniMax 图片或视频创建接口。 */
    @Override public String generationPath(String modelType, String model) { return "video_model".equals(modelType) ? "/v2/video_generation" : "/v1/image_generation"; }
    /** 返回 MiniMax 视频异步查询接口。 */
    @Override public String statusPath(String modelType, String model, String remoteId) { return "video_model".equals(modelType) ? "/v2/query/video_generation" : null; }
    /** 返回 MiniMax 创建接口，用于完整地址配置下的轮询路径换根。 */
    @Override public String operationPath(String modelType, String model) { return generationPath(modelType, model); }
    /** MiniMax 查询接口使用 task_id 查询参数。 */
    @Override public Map<String, String> statusQuery(String modelType, String model, String remoteId) { return "video_model".equals(modelType) ? Map.of("task_id", remoteId) : Map.of(); }

    /** 把统一图片和视频输入映射到 MiniMax 官方 JSON 字段。 */
    @Override public Map<String, Object> request(String modelType, String model, String profile, String prompt,
                                                  List<Map<String, Object>> inputs, Map<String, Object> options) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("model", model);
        if ("video_model".equals(modelType)) {
            List<Map<String, Object>> content = new ArrayList<>();
            if (prompt != null && !prompt.isBlank()) content.add(Map.of("type", "text", "text", prompt));
            for (Map<String, Object> input : inputs == null ? List.<Map<String, Object>>of() : inputs) {
                String kind = String.valueOf(input.get("kind"));
                if ("IMAGE".equals(kind)) content.add(mediaContent("image_url", input));
                else if ("VIDEO".equals(kind)) content.add(mediaContent("video_url", input));
                else if ("AUDIO".equals(kind)) content.add(mediaContent("audio_url", input));
            }
            if (!content.isEmpty()) body.put("content", content);
        } else {
            if (prompt != null && !prompt.isBlank()) body.put("prompt", prompt);
            List<Map<String, Object>> references = new ArrayList<>();
            for (Map<String, Object> input : inputs == null ? List.<Map<String, Object>>of() : inputs)
                if ("IMAGE".equals(input.get("kind"))) references.add(Map.of("type", "character", "image_file", input.get("data")));
            if (!references.isEmpty()) body.put("subject_reference", references);
        }
        if (options != null) options.forEach((key, value) -> body.put("watermark".equals(key) ? "aigc_watermark" : key, value));
        return body;
    }

    /** 构造 MiniMax content 媒体对象并保留平台输入角色。 */
    private Map<String, Object> mediaContent(String type, Map<String, Object> input) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("type", type); value.put(type, Map.of("url", input.get("data")));
        if (input.get("role") != null && !String.valueOf(input.get("role")).isBlank()) value.put("role", input.get("role"));
        return value;
    }

    /** 返回 MiniMax 图片和视频的动态能力目录。 */
    @Override public Map<String, Object> capabilities(String modelType, String model, String profile) {
        if ("video_model".equals(modelType)) return Map.of("profile", effective(model, profile), "type", modelType, "label", model,
            "modes", List.of("TEXT_TO_VIDEO", "IMAGE_TO_VIDEO", "FIRST_LAST_FRAME_TO_VIDEO", "REFERENCE_IMAGE_TO_VIDEO"),
            "options", Map.of("resolution", List.of("480P", "768P", "2K"), "ratio", List.of("adaptive", "21:9", "16:9", "4:3", "1:1", "3:4", "9:16"), "duration", Map.of("min", 4, "max", 15), "watermark", List.of(true, false)),
            "async", true, "imageLimit", 3, "videoLimit", 3, "audioLimit", 3, "totalLimit", 9);
        return Map.of("profile", effective(model, profile), "type", modelType, "label", model,
            "modes", List.of("TEXT_TO_IMAGE", "IMAGE_TO_IMAGE", "IMAGE_TEXT_TO_IMAGE"),
            "options", Map.of("aspect_ratio", List.of("1:1", "4:3", "3:4", "16:9", "9:16"), "response_format", List.of("url", "base64"), "n", Map.of("min", 1, "max", 9), "prompt_optimizer", List.of(true, false), "watermark", List.of(true, false)),
            "async", false, "imageLimit", 9, "videoLimit", 0, "audioLimit", 0, "totalLimit", 9);
    }
    /** MiniMax 支持将平台已落盘素材作为远程输入。 */
    @Override public boolean supportsRemoteInputs() { return true; }
    /** 返回 MiniMax 官方单次素材数量上限。 */
    @Override public int maxInputs(String modelType, String model, String profile) { return 9; }
    /** 判断名称是否包含 MiniMax 相关标识。 */
    private boolean contains(String value) { String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT); return normalized.contains("minimax") || normalized.contains("hailuo") || normalized.contains("video-01") || normalized.contains("image-01"); }
    /** 选择显式画像或官方模型名作为目录展示值。 */
    private String effective(String model, String profile) { return profile == null || profile.isBlank() ? model : profile; }
}
