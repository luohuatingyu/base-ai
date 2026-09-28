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
        when(management.models()).thenReturn(List.of(image, mixed));
        assertEquals(List.of("image"), service.models().stream().map(MediaGenerationService.ModelOption::name).toList());
    }
}
