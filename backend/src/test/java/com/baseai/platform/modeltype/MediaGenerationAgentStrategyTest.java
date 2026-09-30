package com.baseai.platform.modeltype;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证媒体供应商 Agent 策略的选择、请求映射和能力目录。 */
class MediaGenerationAgentStrategyTest {
    /** 内置模型画像必须路由到对应专用策略，未知供应商回退通用策略。 */
    @Test
    void resolvesBuiltInStrategiesAndGenericFallback() {
        MediaGenerationAgentRegistry registry = new MediaGenerationAgentRegistry();
        assertEquals("ARK", registry.protocol("ark", "doubao-seedream-5-0-pro", ""));
        assertEquals("OPENAI", registry.protocol("openai", "gpt-image-2", ""));
        assertEquals("GEMINI", registry.protocol("google", "veo-3.1-generate-001", ""));
        assertEquals("GENERIC", registry.protocol("custom", "custom-image", ""));
    }

    /** OpenAI 图片请求必须使用官方模型字段并保留参考图。 */
    @Test
    void mapsOpenAiImageAndVideoRequests() {
        OpenAiMediaGenerationAgentStrategy strategy = new OpenAiMediaGenerationAgentStrategy();
        Map<String, Object> image = strategy.request("image_model", "gpt-image-2", "", "poster",
            List.of(input("IMAGE", "data:image/png;base64,YQ==", "image/png")), Map.of("size", "1024x1024"));
        assertEquals("gpt-image-2", image.get("model"));
        assertEquals("data:image/png;base64,YQ==", image.get("image"));
        assertEquals("/images/generations", strategy.generationPath("image_model", "gpt-image-2"));

        Map<String, Object> video = strategy.request("video_model", "sora-2", "", "camera move",
            List.of(input("IMAGE", "data:image/png;base64,YQ==", "image/png")), Map.of("duration", 8));
        assertEquals(8, video.get("seconds"));
        assertEquals("data:image/png;base64,YQ==", video.get("input_reference"));
        assertEquals("/videos/task-1", strategy.statusPath("video_model", "sora-2", "task-1"));
    }

    /** Gemini 请求必须转换为 contents/parts/inline_data，并可读取文本 Data URL。 */
    @Test
    void mapsGeminiMultimodalRequest() {
        GeminiMediaGenerationAgentStrategy strategy = new GeminiMediaGenerationAgentStrategy();
        Map<String, Object> body = strategy.request("video_model", "veo-3.1-generate-001", "", "animate",
            List.of(input("IMAGE", "data:image/png;base64,YQ==", "image/png")), Map.of("duration", 8));
        List<?> contents = (List<?>) body.get("contents");
        Map<?, ?> content = (Map<?, ?>) contents.get(0);
        List<?> parts = (List<?>) content.get("parts");
        assertEquals("animate", ((Map<?, ?>) parts.get(0)).get("text"));
        assertEquals("YQ==", ((Map<?, ?>) ((Map<?, ?>) parts.get(1)).get("inline_data")).get("data"));
        assertEquals("/operations/task-1", strategy.statusPath("video_model", "veo-3.1", "task-1"));
    }

    /** 所有专用策略都必须返回前端可用的模式目录，未知模式不得误报支持。 */
    @Test
    void exposesCapabilitiesAndRejectsUnknownMode() {
        MediaGenerationAgentRegistry registry = new MediaGenerationAgentRegistry();
        Map<String, Object> openAi = registry.capabilities("openai", "sora-2", "", "video_model");
        assertTrue(((List<?>) openAi.get("modes")).contains("IMAGE_TO_VIDEO"));
        assertFalse(registry.resolve("openai", "sora-2", "").supportsMode("video_model", "sora-2", "", "DRAFT_VIDEO"));
        assertTrue(MediaGenerationAgentRegistry.knownProfile("gemini", "[video_model]"));
        assertFalse(MediaGenerationAgentRegistry.knownProfile("unknown", "[video_model]"));
    }

    /** 构造明确的 Data URL 输入，避免测试依赖外部网络或文件。 */
    private static Map<String, Object> input(String kind, String data, String contentType) {
        return Map.of("kind", kind, "data", data, "content_type", contentType);
    }
}
