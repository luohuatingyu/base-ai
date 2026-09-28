package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 覆盖媒体生成入口的类型隔离、输入边界和模型目录脱敏行为。 */
class MediaGenerationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LlmManagementService management = mock(LlmManagementService.class);
    private final LlmUsageService usage = mock(LlmUsageService.class);
    private final MediaGenerationService service = new MediaGenerationService(jdbc, management, usage, new ObjectMapper());

    @AfterEach
    void clearAuth() { AuthContext.clear(); }

    /** 未登录和非法模型类型在建立用量记录或访问供应商前被拒绝。 */
    @Test
    void rejectsUnauthenticatedAndMixedTypeRequests() {
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "image_model", 1L, "a cat", null, Map.of())));
        AuthContext.set(new AuthUser(1L, "user", Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "text_model", 1L, "a cat", null, Map.of())));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "image_model", 1L, "a cat", null, Map.of("duration", 5))));
    }

    /** 不接受超长提示词、非正数或未知控制参数，防止请求绕过供应商隔离。 */
    @Test
    void rejectsBoundaryAndUnknownOptions() {
        AuthContext.set(new AuthUser(1L, "user", Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "video_model", 1L, " ", null, Map.of())));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "video_model", 1L, "video", null, Map.of("fps", 121))));
        assertThrows(BusinessException.class, () -> service.generate(new MediaGenerationService.GenerateRequest(
            "video_model", 1L, "video", null, Map.of("endpoint", "/internal"))));
    }

    /** 生成模型目录只返回单一生成类型，不能把视觉或混合模型误展示为生成模型。 */
    @Test
    void generationModelDirectoryIsTypeSpecific() {
        when(management.providers()).thenReturn(List.of(new LlmManagementService.ProviderView(
            2L, "provider", "Provider", "https://provider.example", "******", 4, "PROVIDER", 60,
            "reasoning_effort", true)));
        var image = new com.baseai.platform.domain.LlmModel();
        image.setProviderId(2L); image.setName("image"); image.setModelName("image-1");
        image.setEnabled(true); image.setSupportedModelTypes(List.of("image_model"));
        var mixed = new com.baseai.platform.domain.LlmModel();
        mixed.setProviderId(2L); mixed.setName("mixed"); mixed.setModelName("mixed");
        mixed.setEnabled(true); mixed.setSupportedModelTypes(List.of("image_model", "video_model"));
        var text = new com.baseai.platform.domain.LlmModel();
        text.setProviderId(2L); text.setName("text"); text.setModelName("deepseek-chat");
        text.setEnabled(true); text.setSupportedModelTypes(List.of("text_model"));
        when(management.models()).thenReturn(List.of(image, mixed, text));
        assertEquals(List.of("image"), service.models().stream().map(MediaGenerationService.ModelOption::name).toList());
    }

    /** 图片三种生成模式分别接受文生图、图生图和图文生图输入。 */
    @Test
    void acceptsImageGenerationModesAndInputs() throws Exception {
        var image = imageInput();
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "cat", null, "TEXT_TO_IMAGE", List.of(), Map.of())));
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "", null, "IMAGE_TO_IMAGE", List.of(image), Map.of())));
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "make it blue", null, "IMAGE_TEXT_TO_IMAGE", List.of(image), Map.of())));
    }

    /** 视频模式可以分别接收图片或视频，并拒绝错误的参考媒体类型。 */
    @Test
    void acceptsVideoImageOrVideoInputsAndRejectsMismatch() throws Exception {
        var image = imageInput();
        var video = new MediaGenerationService.MediaInput("VIDEO", "clip.mp4", "video/mp4", dataUrl("video/mp4", 3));
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("video_model", 1L, "animate", null, "IMAGE_TO_VIDEO", List.of(image), Map.of())));
        assertDoesNotThrow(() -> validate(new MediaGenerationService.GenerateRequest("video_model", 1L, "", null, "VIDEO_TO_VIDEO", List.of(video), Map.of())));
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest("video_model", 1L, "video", null, "VIDEO_TO_VIDEO", List.of(image), Map.of())));
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "image", null, "IMAGE_TO_IMAGE", List.of(video), Map.of())));
    }

    /** 上传内容必须使用受控 Data URL，并限制类型、名称和解码后大小。 */
    @Test
    void rejectsUnsafeGenerationInputs() throws Exception {
        var image = imageInput();
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "image", null, "IMAGE_TO_IMAGE", List.of(new MediaGenerationService.MediaInput("IMAGE", "../x.png", "image/png", image.dataUrl())), Map.of())));
        assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest("image_model", 1L, "image", null, "IMAGE_TO_IMAGE", List.of(new MediaGenerationService.MediaInput("IMAGE", "x.png", "image/png", "https://example.com/x.png")), Map.of())));
    }

    /** 各参考媒体模式中的空元素均返回业务校验错误，不触发空指针异常。 */
    @Test
    void rejectsNullMediaElementsAsBusinessErrors() {
        for (String mode : List.of("IMAGE_TO_IMAGE", "IMAGE_TEXT_TO_IMAGE", "IMAGE_TO_VIDEO", "VIDEO_TO_VIDEO")) {
            String type = mode.endsWith("VIDEO") ? "video_model" : "image_model";
            assertThrows(BusinessException.class, () -> validate(new MediaGenerationService.GenerateRequest(
                type, 1L, "reference", null, mode, java.util.Collections.singletonList(null), Map.of())), mode);
        }
    }

    /** 通过反射只测试纯校验分支，避免为边界测试伪造数据库和供应商调用。 */
    private void validate(MediaGenerationService.GenerateRequest request) throws Exception {
        var method = MediaGenerationService.class.getDeclaredMethod("validate", MediaGenerationService.GenerateRequest.class);
        method.setAccessible(true);
        try { method.invoke(service, request); }
        catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    /** 返回最小合法 PNG Data URL。 */
    private MediaGenerationService.MediaInput imageInput() { return new MediaGenerationService.MediaInput("IMAGE", "reference.png", "image/png", dataUrl("image/png", 3)); }

    /** 生成指定字节数的 Base64 Data URL，测试不依赖磁盘文件。 */
    private String dataUrl(String contentType, int bytes) {
        return "data:" + contentType + ";base64," + java.util.Base64.getEncoder().encodeToString(new byte[bytes]);
    }
}
