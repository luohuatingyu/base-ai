package com.baseai.platform.modeltype;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Vidu 图片与视频生成 Agent 策略。 */
public final class ViduMediaGenerationAgentStrategy implements MediaGenerationAgentStrategy {
    /** 返回 Vidu 策略编码。 */
    @Override public String id() { return "VIDU"; }
    /** 识别 Vidu 供应商、模型和显式能力画像。 */
    @Override public boolean supports(String providerCode, String model, String profile) { return contains(providerCode) || contains(model) || contains(profile); }
    /** 返回 Vidu 默认创建接口。 */
    @Override public String generationPath(String modelType, String model) { return "video_model".equals(modelType) ? "/ent/v2/text2video" : "/ent/v2/reference2image"; }
    /** 按素材角色选择 Vidu 图生视频接口。 */
    @Override public String generationPath(String modelType, String model, String prompt, List<Map<String, Object>> inputs, Map<String, Object> options) {
        if (!"video_model".equals(modelType)) return generationPath(modelType, model);
        boolean first = inputs != null && inputs.stream().anyMatch(input -> "first_frame".equalsIgnoreCase(String.valueOf(input.get("role"))));
        boolean last = inputs != null && inputs.stream().anyMatch(input -> "last_frame".equalsIgnoreCase(String.valueOf(input.get("role"))));
        if (first && last) return "/ent/v2/start-end2video";
        boolean reference = inputs != null && inputs.stream().anyMatch(input -> "reference_image".equalsIgnoreCase(String.valueOf(input.get("role"))) || "VIDEO".equals(input.get("kind")));
        if (reference) return "/ent/v2/reference2video";
        boolean image = inputs != null && inputs.stream().anyMatch(input -> "IMAGE".equals(input.get("kind")));
        return image ? "/ent/v2/img2video" : "/ent/v2/text2video";
    }
    /** Vidu 所有生成类型都通过统一任务接口查询。 */
    @Override public String statusPath(String modelType, String model, String remoteId) { return "/ent/v2/tasks/" + remoteId + "/creations"; }
    /** 返回 Vidu 默认创建路径，用于完整地址配置下的轮询换根。 */
    @Override public String operationPath(String modelType, String model) { return generationPath(modelType, model); }

    /** 把统一输入映射为 Vidu 官方请求字段。 */
    @Override public Map<String, Object> request(String modelType, String model, String profile, String prompt, List<Map<String, Object>> inputs, Map<String, Object> options) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("model", model); if (prompt != null && !prompt.isBlank()) body.put("prompt", prompt);
        List<String> images = new ArrayList<>(); List<String> videos = new ArrayList<>(); String audio = null;
        for (Map<String, Object> input : inputs == null ? List.<Map<String, Object>>of() : inputs) {
            if ("IMAGE".equals(input.get("kind"))) images.add(String.valueOf(input.get("data")));
            if ("VIDEO".equals(input.get("kind"))) videos.add(String.valueOf(input.get("data")));
            if ("AUDIO".equals(input.get("kind"))) audio = String.valueOf(input.get("data"));
        }
        if (!images.isEmpty()) body.put("images", images); if (!videos.isEmpty()) body.put("videos", videos); if (audio != null) body.put("audio_url", audio);
        if (options != null) options.forEach((key, value) -> body.put("ratio".equals(key) ? "aspect_ratio" : key, value));
        return body;
    }

    /** 返回 Vidu 图片与视频动态能力目录。 */
    @Override public Map<String, Object> capabilities(String modelType, String model, String profile) {
        if ("video_model".equals(modelType)) return Map.of("profile", effective(model, profile), "type", modelType, "label", model,
            "modes", List.of("TEXT_TO_VIDEO", "IMAGE_TO_VIDEO", "FIRST_LAST_FRAME_TO_VIDEO", "REFERENCE_IMAGE_TO_VIDEO"),
            "options", Map.of("resolution", List.of("540p", "720p", "1080p"), "ratio", List.of("adaptive", "16:9", "9:16", "1:1", "3:4", "4:3", "21:9"), "duration", Map.of("min", 1, "max", 60), "seed", Map.of("min", 0, "max", 2147483647)),
            "async", true, "imageLimit", 7, "videoLimit", 2, "audioLimit", 1, "totalLimit", 10);
        List<String> modes = contains(model) && model.toLowerCase(Locale.ROOT).contains("q1") ? List.of("TEXT_TO_IMAGE", "IMAGE_TO_IMAGE") : List.of("TEXT_TO_IMAGE", "IMAGE_TO_IMAGE", "IMAGE_TEXT_TO_IMAGE");
        return Map.of("profile", effective(model, profile), "type", modelType, "label", model, "modes", modes,
            "options", Map.of("aspect_ratio", List.of("1:1", "16:9", "9:16", "4:3", "3:4"), "resolution", List.of("1024x1024", "2048x2048"), "seed", Map.of("min", 0, "max", 2147483647), "watermark", List.of(true, false)),
            "async", true, "imageLimit", 7, "videoLimit", 0, "audioLimit", 0, "totalLimit", 7);
    }
    /** Vidu 支持将平台已落盘素材作为远程输入。 */
    @Override public boolean supportsRemoteInputs() { return true; }
    /** 返回 Vidu 官方单次图片素材数量上限。 */
    @Override public int maxInputs(String modelType, String model, String profile) { return "video_model".equals(modelType) ? 10 : 7; }
    /** Vidu 使用 Token 认证头。 */
    @Override public String authorizationHeader(String apiKey) { return "Token " + apiKey; }
    /** 判断名称是否包含 Vidu 标识。 */
    private boolean contains(String value) { return value != null && value.toLowerCase(Locale.ROOT).contains("vidu"); }
    /** 选择显式画像或官方模型名作为目录展示值。 */
    private String effective(String model, String profile) { return profile == null || profile.isBlank() ? model : profile; }
}
