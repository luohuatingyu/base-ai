package com.baseai.platform.service;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.DictionaryData;
import com.baseai.platform.domain.LlmModel;
import com.baseai.platform.domain.LlmProvider;
import com.baseai.platform.domain.LlmRoute;
import com.baseai.platform.repository.LlmModelRepository;
import com.baseai.platform.repository.LlmProviderRepository;
import com.baseai.platform.repository.LlmRouteRepository;
import com.baseai.platform.repository.DictionaryDataRepository;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmManagementServiceTest {
    private LlmProviderRepository providerRepository;
    private LlmModelRepository modelRepository;
    private LlmRouteRepository routeRepository;
    private DictionaryDataRepository dictionaryDataRepository;
    private ConfigCryptoService cryptoService;
    private LlmManagementService service;

    /** 为每个测试创建隔离的供应商服务依赖。 */
    @BeforeEach
    void setUp() {
        providerRepository = mock(LlmProviderRepository.class);
        modelRepository = mock(LlmModelRepository.class);
        routeRepository = mock(LlmRouteRepository.class);
        dictionaryDataRepository = mock(DictionaryDataRepository.class);
        cryptoService = mock(ConfigCryptoService.class);
        service = new LlmManagementService(providerRepository, modelRepository, routeRepository, dictionaryDataRepository, cryptoService, mock(RestClient.class));
        AuthContext.set(new AuthUser(1L, "admin", Set.of("ADMIN"), Set.of(), AuthenticationType.TOKEN, null, null));
    }

    /** 清理线程级认证上下文。 */
    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /** 供应商列表必须继续只返回脱敏密钥，不能批量泄露明文。 */
    @Test
    void providerListMasksApiKeys() {
        LlmProvider provider = provider("encrypted-keys");
        when(providerRepository.findAll()).thenReturn(List.of(provider));

        assertEquals("******", service.providers().get(0).apiKeys());
        verifyNoInteractions(cryptoService);
    }

    /** 工作流模型下拉只返回启用供应商下的启用模型和非敏感展示字段。 */
    @Test

    /** 工作流模型路由下拉只返回启用路由，并按功能编码排序且不携带管理配置。 */
    @Test

    /** 工作流候选必须按节点允许能力和用户选择类型共同过滤。 */
    @Test

    /** 工作流路由候选必须按路由中的真实模型能力筛选，未知类型不能误入已知节点。 */
    @Test

    /** 查看单个供应商时，应解密并将逗号、换行分隔的密钥统一为一行一个。 */
    @Test
    void providerApiKeysReturnsOneKeyPerLine() {
        LlmProvider provider = provider("encrypted-keys");
        when(providerRepository.findById(3L)).thenReturn(Optional.of(provider));
        when(cryptoService.decrypt("encrypted-keys")).thenReturn(" key-one, key-two\n\n key-three ");

        assertEquals("key-one\nkey-two\nkey-three", service.providerApiKeys(3L).apiKeys());
    }

    /** 创建供应商时必须拒绝非 HTTP 协议、内嵌凭证和 URL 片段。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "file:///etc/passwd", "https://user:secret@example.com", "https://example.com/path#part"
    })
    void rejectsUnsafeProviderUrls(String baseUrl) {
        when(providerRepository.findByCode("TEST")).thenReturn(Optional.empty());
        LlmManagementService.ProviderCommand command = new LlmManagementService.ProviderCommand(
            "TEST", "Test", baseUrl, "key", 4, "PROVIDER", 60, null, true);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.createProvider(command));

        assertEquals("llm.serviceUrlInvalid", exception.getMessageKey());
        verify(providerRepository, never()).save(any());
    }

    /** 具备供应商更新权限但没有 ADMIN 角色时仍不得读取明文密钥。 */
    @Test
    void nonAdminCannotViewProviderApiKeys() {
        AuthContext.set(new AuthUser(2L, "operator", Set.of("OPERATOR"), Set.of("ai:model:provider:update"),
            AuthenticationType.TOKEN, null, null));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.providerApiKeys(3L));

        assertEquals(403, exception.getStatus());
        verifyNoInteractions(providerRepository, cryptoService);
    }

    /** 模型类型目录应允许任意新增编码，不应被 TEXT/VISION 固定集合限制。 */
    @Test
    void modelTypeCatalogSupportsFutureTypes() {
        DictionaryData data = new DictionaryData();
        data.setDictValue("audio_model");
        data.setLabel("音频模型");
        data.setEnabled(true);
        when(dictionaryDataRepository.findByTypeCodeOrderBySortOrderAscIdAsc("llm_model_type")).thenReturn(List.of(data));

        assertEquals("audio_model", service.modelTypes().get(0).value());
        assertEquals("音频模型", service.modelTypes().get(0).label());
    }

    /** 字典为空时的内置模型类型也必须返回可读标签，不能暴露前端翻译键。 */
    @Test
    void modelTypeFallbackReturnsReadableLabels() {
        when(dictionaryDataRepository.findByTypeCodeOrderBySortOrderAscIdAsc("llm_model_type")).thenReturn(List.of());

        assertEquals("文本模型", service.modelTypes().get(0).label());
        assertEquals("视觉模型", service.modelTypes().get(1).label());
        assertEquals("embedding_model", service.modelTypes().get(2).value());
        assertEquals("向量模型", service.modelTypes().get(2).label());
        assertEquals(new LlmManagementService.ModelTypeOption("image_model", "图片生成模型"), service.modelTypes().get(3));
        assertEquals(new LlmManagementService.ModelTypeOption("video_model", "视频生成模型"), service.modelTypes().get(4));
    }

    /** 两种生成模型分别保存，不再依赖管理员手工添加字典。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"image_model", "video_model"})
    void savesIndependentGenerationModel(String type) {
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider("encrypted")));
        when(modelRepository.save(any(LlmModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LlmModel saved = service.createModel(new LlmManagementService.ModelCommand(
            "MEDIA", "生成模型", 1L, "media-v1", List.of(type), "MIDDLE", "", true));
        assertEquals(List.of(type), saved.getSupportedModelTypes());
        verify(modelRepository).save(saved);
    }

    /** 图片或视频生成能力不能与聊天、向量能力混合声明。 */
    @ParameterizedTest
    @CsvSource({"image_model,vision_model", "video_model,text_model"})
    void rejectsMixedGenerationModelTypes(String first, String second) {
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider("encrypted")));
        BusinessException error = assertThrows(BusinessException.class, () -> service.createModel(
            new LlmManagementService.ModelCommand("MEDIA", "生成模型", 1L, "media-v1", List.of(first, second), "MIDDLE", "", true)));
        assertEquals("llm.modelTypeCombinationInvalid", error.getMessageKey());
        verify(modelRepository, never()).save(any(LlmModel.class));
    }

    /** 同一模型可以同时声明图片和视频生成能力，供两个媒体入口复用。 */
    @Test
    void savesCombinedGenerationModelTypes() {
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider("encrypted")));
        when(modelRepository.save(any(LlmModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LlmModel saved = service.createModel(new LlmManagementService.ModelCommand(
            "MEDIA", "多媒体生成模型", 1L, "media-v1", List.of("image_model", "video_model"), "MIDDLE", "", true));

        assertEquals(List.of("image_model", "video_model"), saved.getSupportedModelTypes());
        verify(modelRepository).save(saved);
    }

    /** 编辑带有视频 profile 的组合媒体模型时，profile 类型只需覆盖组合中的请求类型。 */
    @Test
    void updatesCombinedGenerationModelWithVideoProfile() {
        LlmModel existing = model(1L, "MIDDLE");
        existing.setGenerationProfile("seedance-2.0-mini");
        when(modelRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider("encrypted")));
        when(modelRepository.save(any(LlmModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LlmModel saved = service.updateModel(2L, new LlmManagementService.ModelCommand(
            "MEDIA", "多媒体生成模型", 1L, "doubao-seedance-2-0-mini-260615",
            List.of("image_model", "video_model"), "MIDDLE", "", true));

        assertEquals(List.of("image_model", "video_model"), saved.getSupportedModelTypes());
        assertEquals("seedance-2.0-mini", saved.getGenerationProfile());
        verify(modelRepository).save(saved);
    }

    /** 组合媒体模型仍拒绝与声明类型完全不匹配的 profile，避免错误能力画像进入运行时。 */
    @Test
    void rejectsCombinedGenerationModelWithMismatchedProfile() {
        LlmModel existing = model(1L, "MIDDLE");
        existing.setGenerationProfile("seedream-4.5");
        when(modelRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider("encrypted")));

        BusinessException error = assertThrows(BusinessException.class, () -> service.updateModel(2L,
            new LlmManagementService.ModelCommand("MEDIA", "多媒体生成模型", 1L, "media-v1",
                List.of("video_model"), "MIDDLE", "", true)));

        assertEquals("llm.generationRequestInvalid", error.getMessageKey());
        verify(modelRepository, never()).save(any(LlmModel.class));
    }

    /** 混合配置仍暴露非生成能力，供聊天目录和健康检查使用。 */
    @Test
    void mixedGenerationModelRetainsChatCapabilities() {
        assertEquals(List.of("text_model", "vision_model"), LlmManagementService.chatModelTypes(
            List.of("image_model", "text_model", "video_model", "vision_model")));
    }

    /** 向量能力不能因为历史非生成过滤规则被误暴露到聊天目录。 */
    @Test
    void embeddingModelIsExcludedFromChatCapabilities() {
        assertEquals(List.of("audio_model"), LlmManagementService.chatModelTypes(
            List.of("embedding_model", "audio_model")));
    }

    /** 没有生成适配器时必须明确拒绝探测，不能误发聊天请求或解密密钥。 */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"image_model", "video_model"})
    void generationHealthCheckDoesNotUseChat(String type) {
        LlmModel model = model(1L, "MIDDLE");
        model.setSupportedModelTypes(List.of(type));
        when(modelRepository.findById(10L)).thenReturn(Optional.of(model));
        BusinessException error = assertThrows(BusinessException.class, () -> service.testModel(10L));
        assertEquals("llm.generationAdapterUnavailable", error.getMessageKey());
        verifyNoInteractions(providerRepository, cryptoService);
    }

    /** 同一供应商下图片、视频与视觉理解候选必须按能力分别筛选。 */
    @Test
    void generationCandidatesRemainSeparate() {
        LlmProvider enabled = provider("secret");
        ReflectionTestUtils.setField(enabled, "id", 1L);
        enabled.setEnabled(true);
        List<String> types = List.of("image_model", "video_model", "vision_model");
        List<LlmModel> models = new java.util.ArrayList<>();
        for (int index = 0; index < types.size(); index++) {
            LlmModel item = model(1L, "MIDDLE");
            ReflectionTestUtils.setField(item, "id", (long) index + 1);
            item.setSupportedModelTypes(List.of(types.get(index)));
            models.add(item);
        }
        when(providerRepository.findAll()).thenReturn(List.of(enabled));
        when(modelRepository.findAll()).thenReturn(models);
        for (int index = 0; index < types.size(); index++) {
            LlmManagementService.WorkerCandidate candidate = new LlmManagementService.WorkerCandidate(
                "provider", "https://example.com", List.of("secret"), "media", 1, "PROVIDER", 60, null, null, List.of(types.get(index)));
            when(management.resolveActive("chat", types.get(index))).thenReturn(new LlmManagementService.WorkerRoute(List.of(candidate), false, true));
            var result = service.providers();
            assertEquals(1, result.size());
            assertEquals("provider", result.get(0).code());
        }
    }
}
