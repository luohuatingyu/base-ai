package com.baseai.platform.modeltype;

import com.baseai.platform.common.BusinessException;

import java.net.URI;
import java.util.*;

/** 将平台媒体输入转换为火山方舟 Seedream/Seedance 请求，并公开可供前端渲染的能力目录。 */
public final class ArkMediaProtocol {
    private static final Set<String> IMAGE_OPTIONS = Set.of("size", "response_format", "sequential_image_generation",
        "sequential_image_generation_options", "stream", "tools", "background", "output_format", "watermark",
        "seed", "optimize_prompt_options", "layer_decomposition");
    private static final Set<String> VIDEO_OPTIONS = Set.of("callback_url", "return_last_frame", "generate_audio", "draft",
        "tools", "safety_identifier", "service_tier", "execution_expires_after", "resolution", "ratio", "duration",
        "frames", "seed", "camera_fixed", "watermark", "output_format", "omni_reference_task_type");
    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https");

    private ArkMediaProtocol() {}

    /** 构造官方图片或视频请求；平台内部的 inputs、文件名和控制字段不会透传。 */
    public static Map<String, Object> request(String type, String model, String profile, String prompt,
                                               List<Map<String, Object>> inputs, Map<String, Object> options) {
        String normalizedType = normalize(type);
        if (!Set.of("image_model", "video_model").contains(normalizedType) || blank(model)) invalid();
        Map<String, Object> safeOptions = options == null ? Map.of() : new LinkedHashMap<>(options);
        Set<String> allowed = "image_model".equals(normalizedType) ? IMAGE_OPTIONS : VIDEO_OPTIONS;
        if (!allowed.containsAll(safeOptions.keySet())) invalid();
        validateOptions(normalizedType, safeOptions);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (prompt != null && !prompt.isBlank()) body.put("prompt", prompt);
        List<Map<String, Object>> values = inputs == null ? List.of() : inputs;
        if ("image_model".equals(normalizedType)) {
            List<String> images = values.stream().filter(item -> "IMAGE".equalsIgnoreCase(text(item, "kind")))
                .map(item -> resource(item.get("data"))).toList();
            if (!images.isEmpty()) body.put("image", images.size() == 1 ? images.get(0) : images);
            String combined = values.stream().filter(item -> "TEXT".equalsIgnoreCase(text(item, "kind")))
                .map(item -> textContent(resource(item.get("data")))).collect(java.util.stream.Collectors.joining("\n"));
            if (!combined.isBlank()) body.put("prompt", (prompt == null || prompt.isBlank() ? "" : prompt + "\n") + combined);
        } else {
            List<Map<String, Object>> content = new ArrayList<>();
            for (Map<String, Object> input : values) content.add(videoContent(input));
            if (prompt != null && !prompt.isBlank()) content.add(0, Map.of("type", "text", "text", prompt));
            if (!content.isEmpty()) body.put("content", content);
        }
        body.putAll(safeOptions);
        return body;
    }

    /** 返回 profile 的完整能力描述，供前端按模型能力显示参数和输入限制。 */
    public static Map<String, Object> capabilities(String model) {
        return ArkGenerationCapabilities.describe(model);
    }

    /** 返回管理员可为自定义接入点选择的画像。 */
    public static List<String> profiles() { return ArkGenerationCapabilities.PROFILES; }

    /** 判断是否应由方舟适配器处理官方模型名称。 */
    public static boolean supports(String model) {
        String value = normalizeProfile(model);
        return value.contains("seedream") || value.contains("seedance");
    }

    /** 视频输入映射为官方 content 类型和角色，避免把供应商专用字段留在服务层。 */
    private static Map<String, Object> videoContent(Map<String, Object> input) {
        String kind = text(input, "kind").toUpperCase(Locale.ROOT);
        String data = "TASK".equals(kind) ? text(input, "data") : resource(input.get("data"));
        String role = text(input, "role");
        return switch (kind) {
            case "TEXT" -> Map.of("type", "text", "text", textContent(data));
            case "IMAGE" -> role.isBlank() ? Map.of("type", "image_url", "image_url", Map.of("url", data))
                : Map.of("type", "image_url", "image_url", Map.of("url", data), "role", role);
            case "VIDEO" -> role.isBlank() ? Map.of("type", "video_url", "video_url", Map.of("url", data))
                : Map.of("type", "video_url", "video_url", Map.of("url", data), "role", role);
            case "AUDIO" -> role.isBlank() ? Map.of("type", "audio_url", "audio_url", Map.of("url", data))
                : Map.of("type", "audio_url", "audio_url", Map.of("url", data), "role", role);
            case "TASK" -> {
                if (!data.matches("[A-Za-z0-9_-]{1,200}")) invalid();
                yield Map.of("type", "draft_task", "draft_task", Map.of("id", data));
            }
            default -> { invalid(); yield Map.of(); }
        };
    }

    /** 过滤并验证所有官方公共参数，防止调用方借 options 注入 URL、模型和认证字段。 */
    private static void validateOptions(String type, Map<String, Object> options) {
        if (options.containsKey("stream") && !(options.get("stream") instanceof Boolean)) invalid();
        for (String key : List.of("seed", "duration", "frames", "execution_expires_after")) if (options.containsKey(key)) {
            Object value = options.get(key);
            if (!(value instanceof Number number) || number.longValue() < (Set.of("seed", "duration").contains(key) ? -1 : 0) || number.doubleValue() != number.longValue()) invalid();
        }
        if (options.containsKey("duration") && ((Number) options.get("duration")).intValue() == 0) invalid();
        if (options.containsKey("frames") && ((Number) options.get("frames")).intValue() < 1) invalid();
        if (options.containsKey("response_format") && !Set.of("url", "b64_json").contains(String.valueOf(options.get("response_format")))) invalid();
        if (options.containsKey("sequential_image_generation") && !Set.of("auto", "disabled").contains(String.valueOf(options.get("sequential_image_generation")))) invalid();
        if (options.containsKey("background") && !Set.of("transparent", "opaque").contains(String.valueOf(options.get("background")))) invalid();
        if (options.containsKey("service_tier") && !Set.of("default", "flex").contains(String.valueOf(options.get("service_tier")))) invalid();
        if (options.containsKey("ratio") && !String.valueOf(options.get("ratio")).matches("(?:adaptive|auto|[0-9]+(?::[0-9]+))")) invalid();
        if (options.containsKey("callback_url")) validUrl(String.valueOf(options.get("callback_url")));
        if (options.containsKey("sequential_image_generation_options") && !(options.get("sequential_image_generation_options") instanceof Map<?, ?> map
            && map.get("max_images") instanceof Number n && n.intValue() >= 1 && n.intValue() <= 15)) invalid();
        if ("image_model".equals(type) && options.containsKey("duration")) invalid();
    }

    /** 统一描述前端可用的公共选项。 */
    private static Map<String, Object> capability(String type, String profile, List<String> modes, int imageLimit, int videoLimit,
                                                  int audioLimit, int totalLimit, Map<String, Object> features) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("size", Map.of("type", "string")); options.put("response_format", Map.of("type", "string", "enum", List.of("url", "b64_json")));
        options.put("seed", Map.of("type", "integer", "min", 0)); options.put("stream", Map.of("type", "boolean", "default", false));
        options.put("duration", Map.of("type", "integer", "min", 1, "max", 60)); options.put("frames", Map.of("type", "integer", "min", 1));
        options.put("resolution", Map.of("type", "string")); options.put("ratio", Map.of("type", "string"));
        options.put("generate_audio", Map.of("type", "boolean", "default", true)); options.put("return_last_frame", Map.of("type", "boolean", "default", false));
        options.put("watermark", Map.of("type", "boolean", "default", false)); options.put("background", Map.of("type", "string", "enum", List.of("opaque", "transparent")));
        options.put("sequential_image_generation", Map.of("type", "string", "enum", List.of("auto", "disabled")));
        options.put("sequential_image_generation_options", Map.of("type", "object")); options.put("tools", Map.of("type", "array"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile); result.put("label", profile); result.put("type", type); result.put("modes", modes);
        result.put("imageLimit", imageLimit); result.put("videoLimit", videoLimit); result.put("audioLimit", audioLimit); result.put("totalLimit", totalLimit);
        result.put("imageMaxBytes", 30 * 1024 * 1024); result.put("videoMaxBytes", 50 * 1024 * 1024); result.put("audioMaxBytes", 15 * 1024 * 1024);
        result.put("features", features); result.put("options", options);
        return result;
    }

    /** 规范化模型画像名称，允许直接传模型 ID 或人工选择的 profile。 */
    private static String normalizeProfile(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', '-'); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String text(Map<String, Object> input, String key) { return input == null || input.get(key) == null ? "" : String.valueOf(input.get(key)); }
    private static String resource(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.startsWith("data:")) { if (!text.matches("data:[a-z0-9.+-]+/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=]+")) invalid(); return text; }
        validUrl(text); return text;
    }
    /** 将浏览器上传的文本 Data URL 还原为实际提示词，远程文本仍被拒绝。 */
    private static String textContent(String value) {
        if (!value.startsWith("data:text/")) return value;
        int comma = value.indexOf(',');
        if (comma < 0) invalid();
        try { return new String(Base64.getDecoder().decode(value.substring(comma + 1)), java.nio.charset.StandardCharsets.UTF_8); }
        catch (IllegalArgumentException exception) { invalid(); return ""; }
    }
    private static void validUrl(String value) { try { URI uri = URI.create(value); if (!SAFE_SCHEMES.contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) invalid(); } catch (Exception e) { invalid(); } }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void invalid() { throw new BusinessException("llm.generationRequestInvalid"); }
}
