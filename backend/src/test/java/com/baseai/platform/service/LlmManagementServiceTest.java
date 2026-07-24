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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

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
    }

    /** 供应商列表必须继续只返回脱敏密钥，不能批量泄露明文。 */
    @Test
    void providerListMasksApiKeys() {
        LlmProvider provider = provider("encrypted-keys");
        when(providerRepository.findAll()).thenReturn(List.of(provider));

        assertEquals("******", service.providers().get(0).apiKeys());
        verifyNoInteractions(cryptoService);
    }

    /** 查看单个供应商时，应解密并将逗号、换行分隔的密钥统一为一行一个。 */
    @Test
    void providerApiKeysReturnsOneKeyPerLine() {
        LlmProvider provider = provider("encrypted-keys");
        when(providerRepository.findById(3L)).thenReturn(Optional.of(provider));
        when(cryptoService.decrypt("encrypted-keys")).thenReturn(" key-one, key-two\n\n key-three ");

        assertEquals("key-one\nkey-two\nkey-three", service.providerApiKeys(3L).apiKeys());
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
    }

    /** 模型保存应接受字典中新增的类型编码并持久化为支持集合。 */
    @Test
    void savesFutureModelTypeFromCatalog() {
        DictionaryData data = new DictionaryData();
        data.setDictValue("audio_model");
        data.setLabel("音频模型");
        data.setEnabled(true);
        when(dictionaryDataRepository.findByTypeCodeOrderBySortOrderAscIdAsc("llm_model_type")).thenReturn(List.of(data));
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider("encrypted")));
        when(modelRepository.findByCode("AUDIO")).thenReturn(Optional.empty());
        when(modelRepository.save(any(LlmModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LlmModel saved=service.createModel(new LlmManagementService.ModelCommand("AUDIO","音频",1L,"audio-v1",List.of("audio_model"),null,"MIDDLE","",true));

        assertEquals(List.of("audio_model"), saved.getSupportedModelTypes());
    }

    /** 旧版 BOTH 应兼容展开为文本和视觉两个能力编码。 */
    @Test
    void legacyBothModelTypeExpandsToTwoCapabilities() {
        LlmModel model = new LlmModel();
        model.setModelType("BOTH");

        assertEquals(List.of("text_model", "vision_model"), model.getSupportedModelTypes());
        assertEquals("BOTH", model.getModelType());
    }

    /** 单模型直连必须在后端拒绝不支持所选类型的模型。 */
    @Test
    void directModelRejectsUnsupportedType() {
        LlmModel model = new LlmModel();
        model.setEnabled(true);
        model.setModelType("TEXT");
        when(modelRepository.findById(9L)).thenReturn(Optional.of(model));

        assertThrows(BusinessException.class, () -> service.resolveModel(9L, "vision_model", false, null));
        verifyNoInteractions(cryptoService);
    }

    /** 路由类型应从启用模型的能力集合动态推导，支持未来类型。 */
    @Test
    void routeModelTypesAreDerivedFromConfiguredModels() {
        LlmRoute route = new LlmRoute();
        route.setFeatureCode("audio");
        route.setCandidateModelIds("9");
        route.setEnabled(true);
        LlmModel model = new LlmModel();
        model.setEnabled(true);
        model.setModelType("audio_model");
        when(routeRepository.findByFeatureCode("audio")).thenReturn(Optional.of(route));
        when(modelRepository.findById(9L)).thenReturn(Optional.of(model));
        when(providerRepository.findAll()).thenReturn(List.of(provider("encrypted")));

        assertEquals(List.of("audio_model"), service.routeModelTypes("audio"));
    }

    /** 创建供测试使用的供应商实体。 */
    private LlmProvider provider(String encryptedKeys) {
        LlmProvider provider = new LlmProvider();
        provider.setCode("TEST");
        provider.setName("Test provider");
        provider.setBaseUrl("https://example.test");
        provider.setApiKeysEncrypted(encryptedKeys);
        return provider;
    }
}
