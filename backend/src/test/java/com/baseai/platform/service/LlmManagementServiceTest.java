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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
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

    /** 路由同步选择供应商时，只检查当前路由内被选中的供应商模型。 */
    @Test
    void syncRouteChecksOnlySelectedRouteProviders() {
        LlmRoute route = route("1,2", "");
        LlmModel first = model(1L, "MIDDLE");
        LlmModel second = model(2L, "MIDDLE");
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(first, second));
        doReturn(Map.of()).when(routeService).testModel(null);

        List<LlmManagementService.ModelHealthView> results = routeService.syncRoute(8L, List.of(2L));

        assertEquals(1, results.size());
        assertEquals(2L, results.get(0).providerId());
        verify(modelRepository).save(second);
        verify(modelRepository, never()).save(first);
    }

    /** 路由同步未选择供应商时，应检查当前路由供应商池中的全部模型。 */
    @Test
    void syncRouteChecksAllConfiguredProvidersWhenSelectionIsEmpty() {
        LlmRoute route = route("1,2", "");
        LlmModel first = model(1L, "MIDDLE");
        LlmModel second = model(2L, "MIDDLE");
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(first, second));
        doReturn(Map.of()).when(routeService).testModel(null);

        List<LlmManagementService.ModelHealthView> results = routeService.syncRoute(8L, List.of());

        assertEquals(List.of(1L, 2L), results.stream().map(LlmManagementService.ModelHealthView::providerId).toList());
        verify(modelRepository).save(first);
        verify(modelRepository).save(second);
    }

    /** 空供应商池和空候选模型代表全部供应商，但仍需按路由能力过滤模型。 */
    @Test
    void syncRouteTreatsEmptyConfigurationAsAllProviders() {
        LlmRoute route = route("", "");
        LlmModel matching = model(1L, "MIDDLE");
        LlmModel mismatched = model(2L, "HIGH");
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(matching, mismatched));
        doReturn(Map.of()).when(routeService).testModel(null);

        List<LlmManagementService.ModelHealthView> results = routeService.syncRoute(8L, List.of());

        assertEquals(List.of(1L), results.stream().map(LlmManagementService.ModelHealthView::providerId).toList());
        verify(modelRepository).save(matching);
        verify(modelRepository, never()).save(mismatched);
    }

    /** 删除路由供应商时，应同时清理供应商池和旧候选模型并刷新内存。 */
    @Test
    void removeProviderClearsRoutePoolAndLegacyCandidates() {
        LlmRoute route = route("1,2", "10,11");
        LlmModel first = model(1L, "MIDDLE");
        LlmModel second = model(2L, "MIDDLE");
        route.setEnabled(false);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(modelRepository.findById(10L)).thenReturn(Optional.of(first));
        when(modelRepository.findById(11L)).thenReturn(Optional.of(second));

        service.removeProviderFromRoute(8L, 1L);

        assertEquals("2", route.getProviderIds());
        assertEquals("11", route.getCandidateModelIds());
        verify(routeRepository).save(route);
    }

    /** 从隐式全部供应商路由删除成员时，应固化为不含该成员的显式供应商池。 */
    @Test
    void removeProviderFromImplicitAllRoutePersistsRemainingProviders() {
        LlmRoute route = route("", "");
        LlmProvider first = mock(LlmProvider.class);
        LlmProvider second = mock(LlmProvider.class);
        when(first.getId()).thenReturn(1L);
        when(second.getId()).thenReturn(2L);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(providerRepository.findAll()).thenReturn(List.of(first, second));

        service.removeProviderFromRoute(8L, 1L);

        assertEquals("2", route.getProviderIds());
        verify(routeRepository).save(route);
    }

    /** 删除路由最后一个模型供应后应停用路由，避免空池被重新解释为全部供应商。 */
    @Test
    void removeLastProviderDisablesRoute() {
        LlmRoute route = route("1", "");
        route.setEnabled(true);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));

        service.removeProviderFromRoute(8L, 1L);

        assertEquals(false, route.getEnabled());
        verify(routeRepository).save(route);
    }

    /** 已因删除最后供应商而停用的默认路由，不应被后续定时同步重新启用。 */
    @Test
    void ensureDefaultRoutePreservesDisabledState() {
        LlmRoute route = route("", "");
        route.setFeatureCode(LlmManagementService.DEFAULT_ROUTE);
        route.setEnabled(false);
        when(routeRepository.findByFeatureCode(LlmManagementService.DEFAULT_ROUTE)).thenReturn(Optional.of(route));

        service.ensureDefaultRoute();

        assertEquals(false, route.getEnabled());
        verify(routeRepository).save(route);
    }

    /** 同步耗时必须在10秒和30秒边界切换为对应健康状态。 */
    @ParameterizedTest
    @CsvSource({"0,HEALTHY", "9999,HEALTHY", "10000,WARNING", "29999,WARNING", "30000,SLOW"})
    void healthStatusUsesRequiredDurationBoundaries(long durationMs, String expected) {
        assertEquals(expected, LlmManagementService.healthStatus(durationMs));
    }

    /** 创建用于路由同步测试的路由实体。 */
    private LlmRoute route(String providerIds, String candidateModelIds) {
        LlmRoute route = new LlmRoute();
        route.setFeatureCode("chat");
        route.setProviderIds(providerIds);
        route.setCandidateModelIds(candidateModelIds);
        route.setCapabilityLevel("MIDDLE");
        route.setEnabled(false);
        return route;
    }

    /** 创建用于路由同步测试的模型实体。 */
    private LlmModel model(Long providerId, String capabilityLevel) {
        LlmModel model = new LlmModel();
        model.setProviderId(providerId);
        model.setName("model-" + providerId);
        model.setCapabilityLevel(capabilityLevel);
        model.setEnabled(true);
        return model;
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
