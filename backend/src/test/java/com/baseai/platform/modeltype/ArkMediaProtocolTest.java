package com.baseai.platform.modeltype;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证方舟图片和视频请求的字段映射、能力目录和输入安全边界。 */
class ArkMediaProtocolTest {
    /** 图片参考图必须映射到 image，不能把平台内部 inputs 字段发给方舟。 */
    @Test
    void mapsSeedreamInputsAndOptions() {
        Map<String, Object> request = ArkMediaProtocol.request("image_model", "doubao-seedream-4-0-250828",
            "seedream-4.0", "poster", List.of(input("IMAGE", "reference.png", "image/png", "data:image/png;base64,YQ==", null)),
            Map.of("size", "2K", "response_format", "b64_json", "sequential_image_generation", "auto",
                "sequential_image_generation_options", Map.of("max_images", 3)));
        assertEquals("doubao-seedream-4-0-250828", request.get("model"));
        assertEquals("poster", request.get("prompt"));
        assertEquals("data:image/png;base64,YQ==", request.get("image"));
        assertFalse(request.containsKey("inputs"));
        assertEquals("2K", request.get("size"));
        assertEquals(3, ((Map<?, ?>) request.get("sequential_image_generation_options")).get("max_images"));
    }

    /** 视频多模态内容必须按官方 content 结构传递角色和字段。 */
    @Test
    void mapsSeedanceMultimodalContent() {
        Map<String, Object> request = ArkMediaProtocol.request("video_model", "doubao-seedance-2-0-260128",
            "seedance-2.0", "animate", List.of(
                input("IMAGE", "first.png", "image/png", "https://cdn.example/first.png", "first_frame"),
                input("IMAGE", "last.png", "image/png", "https://cdn.example/last.png", "last_frame"),
                input("AUDIO", "voice.mp3", "audio/mpeg", "https://cdn.example/voice.mp3", "reference_audio")),
            Map.of("resolution", "720p", "ratio", "16:9", "duration", 5, "generate_audio", true));
        assertEquals("doubao-seedance-2-0-260128", request.get("model"));
        List<?> content = (List<?>) request.get("content");
        assertEquals(4, content.size());
        assertEquals("text", ((Map<?, ?>) content.get(0)).get("type"));
        assertEquals("image_url", ((Map<?, ?>) content.get(1)).get("type"));
        assertEquals("first_frame", ((Map<?, ?>) content.get(1)).get("role"));
        assertEquals("audio_url", ((Map<?, ?>) content.get(3)).get("type"));
        assertEquals("720p", request.get("resolution"));
    }

    /** 能力目录需要给前端完整的模型差异和参数描述。 */
    @Test
    void exposesCapabilitiesForKnownProfiles() {
        Map<String, Object> capabilities = ArkMediaProtocol.capabilities("doubao-seedream-5-0-260128");
        assertEquals("image_model", capabilities.get("type"));
        assertTrue(((List<?>) capabilities.get("modes")).contains("TEXT_TO_IMAGE"));
        assertTrue(((Map<?, ?>) capabilities.get("features")).containsKey("groups"));
        assertTrue(((Map<?, ?>) capabilities.get("options")).containsKey("sequential_image_generation"));
        assertEquals("video_model", ArkMediaProtocol.capabilities("doubao-seedance-2-5-260628").get("type"));
    }

    /** 未知参数、错误协议类型和危险资源地址必须在网络调用前失败。 */
    @Test
    void rejectsUnsafeOrUnknownArkRequest() {
        assertThrows(BusinessException.class, () -> ArkMediaProtocol.request("image_model", "seedream", "seedream-4.0", "x",
            List.of(input("IMAGE", "x", "image/png", "file:///etc/passwd", null)), Map.of()));
        assertThrows(BusinessException.class, () -> ArkMediaProtocol.request("image_model", "seedream", "seedream-4.0", "x",
            List.of(), Map.of("endpoint", "/internal")));
        assertThrows(BusinessException.class, () -> ArkMediaProtocol.request("video_model", "seedance", "seedance-2.0", "x",
            List.of(), Map.of("duration", 0)));
    }

    /** 构造测试输入，所有数据值均为明确的 URL 或 Data URL。 */
    private static Map<String, Object> input(String kind, String name, String type, String data, String role) {
        return Map.of("kind", kind, "name", name, "content_type", type, "data", data, "role", role == null ? "" : role);
    }
}
