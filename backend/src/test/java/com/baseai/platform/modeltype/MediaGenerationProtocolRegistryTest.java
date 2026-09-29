package com.baseai.platform.modeltype;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证图片和视频协议适配器只负责构造各自的媒体请求。 */
class MediaGenerationProtocolRegistryTest {
    /** 图片协议保留生成模式和参考媒体，选项仍可透传。 */
    @Test
    void imageProtocolCarriesModeAndInputs() {
        var registry = new MediaGenerationProtocolRegistry();
        var request = registry.request("image_model", "image-1", "cat", "IMAGE_TEXT_TO_IMAGE",
            List.of(Map.of("kind", "IMAGE", "data", "data:image/png;base64,AA==")), Map.of("size", "1024x1024"));
        assertEquals("image-1", request.get("model"));
        assertEquals("IMAGE_TEXT_TO_IMAGE", request.get("generation_mode"));
        assertEquals(1, ((List<?>) request.get("inputs")).size());
        assertEquals("1024x1024", request.get("size"));
    }

    /** 两类输出协议均完整保留混合输入的顺序、内容和提示词。 */
    @ParameterizedTest
    @ValueSource(strings = {"image_model", "video_model"})
    void preservesCombinedInputs(String type) {
        List<Map<String, Object>> inputs = List.of(
            Map.of("kind", "TEXT", "data", "data:text/plain;base64,YQ=="),
            Map.of("kind", "IMAGE", "data", "data:image/png;base64,YQ=="),
            Map.of("kind", "VIDEO", "data", "data:video/mp4;base64,YQ=="));
        var body = new MediaGenerationProtocolRegistry().request(type, "model", "instructions", "MULTIMODAL", inputs, Map.of());
        assertEquals(inputs, body.get("inputs"));
        assertEquals("instructions", body.get("prompt"));
        assertEquals("MULTIMODAL", body.get("generation_mode"));
        assertEquals("model", body.get("model"));
    }

    /** 未注册的模型类型不生成请求，避免未知协议误发供应商。 */
    @Test
    void unknownProtocolIsRejectedByEmptyPayload() {
        assertTrue(new MediaGenerationProtocolRegistry().request("text_model", "text", "hello", "TEXT_TO_IMAGE", List.of(), Map.of()).isEmpty());
    }
}
