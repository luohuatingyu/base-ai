package com.baseai.platform.modeltype;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 图片生成协议：保留统一模式和参考图片字段，供应商差异由后续专用适配器覆盖。 */
public final class ImageGenerationProtocolAdapter implements MediaGenerationProtocolAdapter {
    /** 返回图片模型类型。 */
    @Override public String modelType() { return "image_model"; }

    /** 构造兼容 JSON 图片生成请求，并清晰区分文生图与参考图输入。 */
    @Override public Map<String, Object> request(String model, String prompt, String generationMode,
                                                 List<Map<String, Object>> inputs, Map<String, Object> options) {
        if (ArkMediaProtocol.supports(model)) {
            return ArkMediaProtocol.request(modelType(), model, model, prompt, inputs, options);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (options != null) body.putAll(options);
        body.put("model", model);
        body.put("prompt", prompt == null ? "" : prompt);
        body.put("generation_mode", generationMode);
        body.put("inputs", inputs == null ? List.of() : new ArrayList<>(inputs));
        return body;
    }
}
