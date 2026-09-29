package com.baseai.platform.modeltype;

import com.baseai.platform.common.BusinessException;
import java.util.*;

/** 方舟模型版本能力及组合校验；浏览器消费同一目录，后端始终重新校验。 */
public final class ArkGenerationCapabilities {
    public static final List<String> PROFILES = List.of("seedream-4.0", "seedream-4.5", "seedream-5.0-lite", "seedream-5.0-pro", "seedream-5.0-flash",
        "seedance-1.0-pro", "seedance-1.0-pro-fast", "seedance-1.0-lite-i2v", "seedance-1.0-lite-t2v", "seedance-1.5-pro",
        "seedance-2.0", "seedance-2.0-fast", "seedance-2.0-mini", "seedance-2.5");
    private ArkGenerationCapabilities() {}

    /** 把版本化官方标识归一到稳定画像，未知版本不猜测高级能力。 */
    public static String profile(String model) {
        String value = model == null ? "" : model.toLowerCase(Locale.ROOT).replace('_', '-').replace('.', '-');
        for (String item : PROFILES.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            String key = item.replace('.', '-');
            if (value.equals(key) || value.endsWith("-" + key) || value.contains(key + "-")) return item;
        }
        if (value.contains("seedream-5-0-")) return "seedream-5.0-lite";
        return "";
    }

    /** 返回模式、数量、参数和默认值，所有限制由明确版本决定。 */
    public static Map<String, Object> describe(String model) {
        String profile = profile(model);
        if (profile.isBlank()) return Map.of("profile", "", "type", "", "modes", List.of(), "features", Map.of(), "options", Map.of());
        boolean image = profile.startsWith("seedream");
        boolean pro = image && (profile.endsWith("pro") || profile.endsWith("flash"));
        boolean v25 = profile.equals("seedance-2.5");
        boolean v2 = profile.startsWith("seedance-2.") && !v25;
        boolean lite = profile.contains("lite");
        boolean fast = profile.endsWith("fast") || profile.endsWith("mini");
        List<String> modes = new ArrayList<>();
        if (image) {
            modes.addAll(List.of("TEXT_TO_IMAGE", "IMAGE_TEXT_TO_IMAGE", "MULTI_IMAGE_FUSION"));
            if (pro) modes.addAll(List.of("LAYER_DECOMPOSITION", "INTERACTIVE_EDIT", "TRANSPARENT_IMAGE"));
            else modes.addAll(List.of("TEXT_TO_GROUP", "IMAGE_TO_GROUP", "MULTI_IMAGE_TO_GROUP"));
        } else {
            if (!profile.endsWith("i2v")) modes.add("TEXT_TO_VIDEO");
            if (!profile.endsWith("t2v")) modes.add("IMAGE_TO_VIDEO");
            if (!profile.endsWith("t2v") && !profile.equals("seedance-1.0-pro-fast")) modes.add("FIRST_LAST_FRAME_TO_VIDEO");
            if (v2 || v25 || profile.endsWith("i2v")) modes.add("REFERENCE_IMAGE_TO_VIDEO");
            if (v2 || v25) modes.addAll(List.of("VIDEO_TO_VIDEO", "IMAGE_VIDEO_AUDIO_TO_VIDEO", "EDIT_VIDEO", "EXTEND_VIDEO"));
            if (v25 || profile.equals("seedance-1.5-pro")) modes.addAll(List.of("DRAFT_VIDEO", "DRAFT_TO_VIDEO"));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("seed", number(-1, 2147483647));
        options.put("watermark", Map.of("type", "boolean"));
        if (image) {
            options.put("size", Map.of("type", "string"));
            options.put("response_format", choice("url", "b64_json"));
            options.put("output_format", choice("jpeg", "png"));
            options.put("optimize_prompt_options", Map.of("type", "object"));
            if (pro) {
                options.put("layer_decomposition", Map.of("type", "boolean"));
                options.put("interactive_edit", Map.of("type", "boolean"));
                options.put("background", choice("opaque", "transparent"));
            } else {
                options.put("stream", Map.of("type", "boolean"));
                options.put("sequential_image_generation", choice("disabled", "auto"));
                options.put("sequential_image_generation_options", Map.of("type", "object"));
                if (profile.equals("seedream-5.0-lite")) options.put("tools", Map.of("type", "array"));
            }
        } else {
            options.put("resolution", fast ? choice("480p", "720p") : profile.equals("seedance-2.0") ? choice("480p", "720p", "1080p", "4k") : choice("480p", "720p", "1080p"));
            options.put("ratio", choice("adaptive", "16:9", "4:3", "1:1", "3:4", "9:16", "21:9"));
            options.put("duration", number(-1, v25 ? 30 : v2 ? 15 : 12));
            if (!v2) options.put("frames", number(29, 289));
            if (v2 || profile.equals("seedance-1.5-pro")) options.put("generate_audio", Map.of("type", "boolean"));
            options.put("return_last_frame", Map.of("type", "boolean"));
            if (v25 || profile.equals("seedance-1.5-pro")) options.put("draft", Map.of("type", "boolean"));
            if (!v2) options.put("camera_fixed", Map.of("type", "boolean"));
            if (v25) { options.put("output_format", choice("mp4", "mov")); options.put("omni_reference_task_type", choice("auto", "reference", "edit", "extend")); }
            options.put("callback_url", Map.of("type", "string"));
            options.put("service_tier", choice("default", "flex"));
            options.put("execution_expires_after", number(3600, 259200));
            options.put("safety_identifier", Map.of("type", "string"));
            options.put("tools", Map.of("type", "array"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile); result.put("type", image ? "image_model" : "video_model"); result.put("modes", modes);
        result.put("imageLimit", image ? pro ? 10 : 14 : v25 ? 30 : v2 ? 9 : lite ? 4 : 2);
        result.put("videoLimit", v25 ? 10 : v2 ? 3 : 0); result.put("audioLimit", v25 ? 10 : v2 ? 3 : 0);
        result.put("totalLimit", image ? pro ? 10 : 14 : v25 ? 50 : v2 ? 12 : lite ? 4 : 2);
        result.put("imageMaxBytes", image ? 30 * 1024 * 1024 : 30 * 1024 * 1024);
        result.put("videoMaxBytes", 50 * 1024 * 1024); result.put("audioMaxBytes", 15 * 1024 * 1024);
        result.put("durationMin", v2 ? 4 : 2);
        result.put("features", Map.of("groups", image && !pro, "streaming", image && !pro, "search", profile.equals("seedream-5.0-lite"),
            "layers", pro, "transparent", pro, "draft", v25 || profile.equals("seedance-1.5-pro"), "audio", v2 || v25 || profile.equals("seedance-1.5-pro")));
        result.put("options", options);
        return result;
    }

    /** 检查输入组合与模式一致，并把用户模式规范为供应商参数。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> options(String profile, String type, String mode, String prompt,
                                               List<Map<String, Object>> inputs, Map<String, Object> supplied) {
        Map<String, Object> caps = describe(profile);
        if (!type.equals(caps.get("type"))) invalid();
        var options = new LinkedHashMap<>(supplied == null ? Map.<String, Object>of() : supplied);
        var values = inputs == null ? List.<Map<String, Object>>of() : inputs;
        if (values.stream().anyMatch(Objects::isNull)) invalid();
        String normalized = mode == null ? "MULTIMODAL" : mode;
        List<String> modes = (List<String>) caps.get("modes");
        boolean legacy = Set.of("MULTIMODAL", "IMAGE_TO_IMAGE").contains(normalized);
        if (!legacy && !modes.contains(normalized)) invalid();
        long images = count(values, "IMAGE"), videos = count(values, "VIDEO"), audio = count(values, "AUDIO"), tasks = count(values, "TASK");
        if (images > (int) caps.get("imageLimit") || videos > (int) caps.get("videoLimit") || audio > (int) caps.get("audioLimit")
            || images + videos + audio > (int) caps.get("totalLimit")) invalid();
        if (values.stream().anyMatch(v -> !Set.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "TASK").contains(v.get("kind")))) invalid();
        boolean text = prompt != null && !prompt.isBlank() || count(values, "TEXT") > 0;
        if (type.equals("image_model")) {
            if (videos + audio + tasks > 0) invalid();
            if (normalized.equals("LAYER_DECOMPOSITION")) options.put("layer_decomposition", true);
            if (normalized.equals("INTERACTIVE_EDIT")) options.put("interactive_edit", true);
            if (normalized.equals("TRANSPARENT_IMAGE")) { options.put("background", "transparent"); options.put("output_format", "png"); }
            if (normalized.endsWith("GROUP")) options.put("sequential_image_generation", "auto");
            if (!text && !Boolean.TRUE.equals(options.get("layer_decomposition"))) invalid();
            if (normalized.startsWith("TEXT_TO_") && images != 0 || Set.of("IMAGE_TEXT_TO_IMAGE", "IMAGE_TO_GROUP", "LAYER_DECOMPOSITION", "TRANSPARENT_IMAGE", "INTERACTIVE_EDIT").contains(normalized) && images != 1
                || normalized.startsWith("MULTI_IMAGE") && images < 2) invalid();
            if (Boolean.TRUE.equals(options.get("layer_decomposition")) && (images != 1 || Boolean.TRUE.equals(options.get("interactive_edit")))) invalid();
            if (Boolean.TRUE.equals(options.get("interactive_edit")) && images != 1) invalid();
            if ("transparent".equals(options.get("background")) && (images != 1 || "jpeg".equals(options.get("output_format")))) invalid();
            if (options.get("sequential_image_generation_options") instanceof Map<?, ?> group) {
                if (!"auto".equals(options.get("sequential_image_generation")) || group.size() != 1 || !(group.get("max_images") instanceof Number n)
                    || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() + images > 15) invalid();
            }
        } else {
            long first = roleCount(values, "first_frame"), last = roleCount(values, "last_frame");
            boolean frames = first + last > 0;
            if (first > 1 || last > 1 || last > 0 && first != 1 || frames && (videos + audio > 0 || images != first + last)) invalid();
            for (Map<String, Object> value : values) {
                String kind = String.valueOf(value.get("kind")), role = String.valueOf(value.getOrDefault("role", ""));
                if (role.isBlank()) continue;
                if (!switch (kind) {
                    case "IMAGE" -> Set.of("first_frame", "last_frame", "reference_image").contains(role);
                    case "VIDEO" -> role.equals("reference_video");
                    case "AUDIO" -> role.equals("reference_audio");
                    default -> false;
                }) invalid();
            }
            if (normalized.equals("TEXT_TO_VIDEO") && images + videos + audio + tasks != 0 || !text && images + videos + audio + tasks == 0) invalid();
            if (normalized.equals("IMAGE_TO_VIDEO") && (first != 1 || images != 1)
                || normalized.equals("FIRST_LAST_FRAME_TO_VIDEO") && (first != 1 || last != 1)
                || normalized.equals("REFERENCE_IMAGE_TO_VIDEO") && (images < 1 || frames || videos + audio > 0)
                || Set.of("VIDEO_TO_VIDEO", "EDIT_VIDEO", "EXTEND_VIDEO").contains(normalized) && (videos < 1 || frames)
                || normalized.equals("IMAGE_VIDEO_AUDIO_TO_VIDEO") && (images + videos + audio == 0 || frames)) invalid();
            if (frames && !modes.contains(last > 0 ? "FIRST_LAST_FRAME_TO_VIDEO" : "IMAGE_TO_VIDEO")) invalid();
            if (normalized.equals("DRAFT_VIDEO")) options.put("draft", true);
            if (normalized.equals("DRAFT_TO_VIDEO")) {
                if (tasks != 1 || values.size() != 1 || text || !modes.contains("DRAFT_TO_VIDEO")) invalid();
                if (options.keySet().stream().anyMatch(key -> Set.of("duration", "ratio", "generate_audio", "frames", "draft").contains(key))) invalid();
            } else if (tasks > 0) invalid();
            boolean v25 = caps.get("profile").equals("seedance-2.5");
            if (v25 && (frames || Set.of("EDIT_VIDEO", "EXTEND_VIDEO").contains(normalized))) {
                if (options.containsKey("ratio") && !"adaptive".equals(options.get("ratio"))) invalid();
                options.put("ratio", "adaptive");
            }
            if (v25 && Set.of("EDIT_VIDEO", "EXTEND_VIDEO").contains(normalized)) options.put("omni_reference_task_type", normalized.equals("EDIT_VIDEO") ? "edit" : "extend");
            if (v25 && normalized.equals("EDIT_VIDEO")) {
                if (options.containsKey("duration") && !Objects.equals(options.get("duration"), -1)) invalid();
                options.put("duration", -1);
            }
            if (options.containsKey("duration") && options.containsKey("frames")) invalid();
            if (options.get("duration") instanceof Number n && n.intValue() != -1 && n.intValue() < (int) caps.get("durationMin")) invalid();
            if (Boolean.TRUE.equals(options.get("draft")) && options.containsKey("resolution") && !"480p".equals(options.get("resolution"))) invalid();
        }
        Map<String, Map<String, Object>> schemas = (Map<String, Map<String, Object>>) caps.get("options");
        for (var entry : options.entrySet()) {
            Map<String, Object> schema = schemas.get(entry.getKey()); Object value = entry.getValue();
            if (schema == null || value == null) invalid();
            switch ((String) schema.get("type")) {
                case "boolean" -> { if (!(value instanceof Boolean)) invalid(); }
                case "integer" -> { if (!(value instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < ((Number) schema.get("min")).longValue() || n.longValue() > ((Number) schema.get("max")).longValue()) invalid(); }
                case "string" -> { if (!(value instanceof String) || schema.containsKey("enum") && !((List<?>) schema.get("enum")).contains(value)) invalid(); }
                case "object" -> { if (!(value instanceof Map)) invalid(); }
                case "array" -> { if (!(value instanceof List)) invalid(); }
                default -> invalid();
            }
        }
        return options;
    }

    /** 统计输入类型，以同一算法校验总数与模式约束。 */
    private static long count(List<Map<String, Object>> inputs, String kind) { return inputs.stream().filter(v -> kind.equals(v.get("kind"))).count(); }
    /** 首尾帧角色必须唯一，不能与参考素材混用。 */
    private static long roleCount(List<Map<String, Object>> inputs, String role) { return inputs.stream().filter(v -> role.equals(v.get("role"))).count(); }
    /** 声明有界整数参数。 */
    private static Map<String, Object> number(int min, int max) { return Map.of("type", "integer", "min", min, "max", max); }
    /** 声明受控枚举参数。 */
    private static Map<String, Object> choice(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
    /** 校验失败使用稳定错误码，避免供应商凭据或原始响应泄漏。 */
    private static void invalid() { throw new BusinessException("llm.generationRequestInvalid"); }
}
