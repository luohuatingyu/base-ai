package com.baseai.platform.modeltype;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 验证图片和视频按模型版本开放模式，并拒绝跨模式素材组合。 */
class ArkGenerationCapabilitiesTest {
    /** Seedream lite 支持单图、多图和组图，pro 支持图层而不支持组图。 */
    @Test
    void describesVersionSpecificImageModes() {
        var lite = ArkGenerationCapabilities.describe("doubao-seedream-4-5-251128");
        var pro = ArkGenerationCapabilities.describe("doubao-seedream-5-0-pro-260628");
        assertTrue(((List<?>) lite.get("modes")).contains("MULTI_IMAGE_TO_GROUP"));
        assertTrue((Boolean) ((Map<?, ?>) lite.get("features")).get("groups"));
        assertTrue(((List<?>) pro.get("modes")).contains("LAYER_DECOMPOSITION"));
        assertFalse(((Map<?, ?>) pro.get("features")).containsKey("groups") && Boolean.TRUE.equals(((Map<?, ?>) pro.get("features")).get("groups")));
    }

    /** 首尾帧、视频参考和样片模式均由 Seedance 2.5 能力目录声明。 */
    @Test
    void describesVersionSpecificVideoModes() {
        var value = ArkGenerationCapabilities.describe("doubao-seedance-2-5-260628");
        assertTrue(((List<?>) value.get("modes")).containsAll(List.of("TEXT_TO_VIDEO", "FIRST_LAST_FRAME_TO_VIDEO", "STORYBOARD_TO_VIDEO", "ONE_CLICK_VIDEO", "VIDEO_TRANSITION", "EDIT_VIDEO", "EXTEND_VIDEO", "DRAFT_TO_VIDEO")));
        assertEquals(4, value.get("durationMin"));
        assertEquals(50, value.get("totalLimit"));
    }

    /** 多图融合、组图数量和首尾帧角色必须满足供应商边界。 */
    @Test
    void validatesImageAndFrameCombinations() {
        var images = List.of(input("IMAGE", "reference_image"), input("IMAGE", "reference_image"));
        assertDoesNotThrow(() -> ArkGenerationCapabilities.options("doubao-seedream-4-5-251128", "image_model", "MULTI_IMAGE_TO_GROUP", "combine", images, Map.of("sequential_image_generation_options", Map.of("max_images", 3))));
        assertThrows(BusinessException.class, () -> ArkGenerationCapabilities.options("doubao-seedream-4-5-251128", "image_model", "MULTI_IMAGE_TO_GROUP", "combine", images, Map.of("sequential_image_generation_options", Map.of("max_images", 14))));
        var frame = List.of(input("IMAGE", "first_frame"), input("IMAGE", "last_frame"));
        assertDoesNotThrow(() -> ArkGenerationCapabilities.options("doubao-seedance-2.5", "video_model", "FIRST_LAST_FRAME_TO_VIDEO", "transition", frame, Map.of("ratio", "adaptive", "duration", 5)));
        assertThrows(BusinessException.class, () -> ArkGenerationCapabilities.options("doubao-seedance-2.5", "video_model", "FIRST_LAST_FRAME_TO_VIDEO", "transition", List.of(input("IMAGE", "last_frame")), Map.of()));
    }

    /** 非法参数、角色和未经授权的草稿引用在网络调用前被阻止。 */
    @Test
    void rejectsUnsafeCombinations() {
        assertThrows(BusinessException.class, () -> ArkGenerationCapabilities.options("doubao-seedance-2.5", "video_model", "TEXT_TO_VIDEO", "x", List.of(input("VIDEO", "reference_video")), Map.of("ratio", "bad")));
        assertThrows(BusinessException.class, () -> ArkGenerationCapabilities.options("doubao-seedance-2.5", "video_model", "DRAFT_TO_VIDEO", "", List.of(input("TASK", "")), Map.of("duration", 5)));
    }

    /** 编辑、样片和扩展模式必须能在其锁定参数下通过服务层校验。 */
    @Test
    void acceptsLockedVideoModesAndRemovesSyntheticInteractiveOption() {
        var video = List.of(input("VIDEO", "reference_video"));
        var edit = ArkGenerationCapabilities.options("doubao-seedance-2-5-260628", "video_model", "EDIT_VIDEO", "编辑视频", video,
            Map.of("ratio", "adaptive", "duration", -1, "output_format", "mov"));
        assertEquals(-1, edit.get("duration"));
        assertEquals("adaptive", edit.get("ratio"));
        assertEquals("mov", edit.get("output_format"));

        var draft = ArkGenerationCapabilities.options("doubao-seedance-2-5-260628", "video_model", "DRAFT_VIDEO", "draft", List.of(),
            Map.of("resolution", "480p", "duration", 5, "draft", true));
        assertEquals(Boolean.TRUE, draft.get("draft"));

        var draftToVideo = ArkGenerationCapabilities.options("doubao-seedance-2-5-260628", "video_model", "DRAFT_TO_VIDEO", "", List.of(input("TASK", "")),
            Map.of("watermark", false));
        assertEquals(Boolean.FALSE, draftToVideo.get("watermark"));

        var interactive = ArkGenerationCapabilities.options("doubao-seedream-5-0-pro-260628", "image_model", "INTERACTIVE_EDIT", "edit", List.of(input("IMAGE", "reference_image")), Map.of());
        assertFalse(interactive.containsKey("interactive_edit"));
    }

    /** 分镜、一键成片和无缝转场必须接受官方参考素材组合，并拒绝不足的转场输入。 */
    @Test
    void validatesExtendedSeedanceModes() {
        assertDoesNotThrow(() -> ArkGenerationCapabilities.options("doubao-seedance-2-5-260628", "video_model", "STORYBOARD_TO_VIDEO", "storyboard",
            List.of(input("IMAGE", "reference_image")), Map.of("ratio", "16:9", "duration", 5)));
        assertDoesNotThrow(() -> ArkGenerationCapabilities.options("doubao-seedance-2-5-260628", "video_model", "ONE_CLICK_VIDEO", "one click",
            List.of(input("VIDEO", "reference_video")), Map.of("duration", 5)));
        assertDoesNotThrow(() -> ArkGenerationCapabilities.options("doubao-seedance-2-5-260628", "video_model", "VIDEO_TRANSITION", "transition",
            List.of(input("VIDEO", "reference_video"), input("VIDEO", "reference_video")), Map.of("duration", 5)));
        assertThrows(BusinessException.class, () -> ArkGenerationCapabilities.options("doubao-seedance-2-5-260628", "video_model", "VIDEO_TRANSITION", "transition",
            List.of(input("VIDEO", "reference_video")), Map.of("duration", 5)));
    }

    /** 构造协议层输入，内容本身由服务层另行验证。 */
    private static Map<String, Object> input(String kind, String role) { return Map.of("kind", kind, "role", role, "data", "data:image/png;base64,YQ=="); }
}
