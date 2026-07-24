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
import org.springframework.test.util.ReflectionTestUtils;
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

    /** 路由同步应忽略旧请求筛选，并自动移除没有匹配模型能力的供应商。 */
    @Test
    void syncRouteIgnoresRequestedSelectionAndRemovesCapabilityMismatch() {
        LlmRoute route = route("1,2", "");
        LlmModel first = model(1L, "MIDDLE");
        LlmModel second = model(2L, "HIGH");
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(first, second));
        doReturn(Map.of()).when(routeService).testModel(null);

        List<LlmManagementService.ModelHealthView> results = routeService.syncRoute(8L, List.of(2L));

        assertEquals(List.of(1L), results.stream().map(LlmManagementService.ModelHealthView::providerId).toList());
        assertEquals("1", route.getProviderIds());
        verify(modelRepository).save(first);
        verify(modelRepository, never()).save(second);
        verify(routeRepository).save(route);
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

    /** 开启思考模式时，只保留同时匹配能力和思考级别的供应商。 */
    @Test
    void syncRouteRemovesProviderWithoutMatchingThinkingLevel() {
        LlmRoute route = route("1,2", "");
        route.setEnableThinking(true);
        route.setThinkingLevel("HIGH");
        LlmModel matching = model(1L, "MIDDLE");
        matching.setThinkingLevels("HIGH=high");
        LlmModel mismatched = model(2L, "MIDDLE");
        mismatched.setThinkingLevels("MEDIUM=medium");
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(matching, mismatched));
        doReturn(Map.of()).when(routeService).testModel(null);

        List<LlmManagementService.ModelHealthView> results = routeService.syncRoute(8L, List.of());

        assertEquals(List.of(1L), results.stream().map(LlmManagementService.ModelHealthView::providerId).toList());
        assertEquals("1", route.getProviderIds());
        verify(modelRepository).save(matching);
        verify(modelRepository, never()).save(mismatched);
        verify(routeRepository).save(route);
    }

    /** 所有供应商均无有效匹配模型时，应跳过检查、清空供应商池并停用路由。 */
    @Test
    void syncRouteDisablesRouteWhenAllProvidersAreUnmatched() {
        LlmRoute route = route("1", "");
        route.setEnabled(true);
        LlmModel mismatched = model(1L, "HIGH");
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(mismatched));
        doReturn(Map.of()).when(routeService).testModel(null);

        List<LlmManagementService.ModelHealthView> results = routeService.syncRoute(8L, List.of());

        assertEquals(List.of(), results);
        assertEquals("", route.getProviderIds());
        assertEquals(false, route.getEnabled());
        verify(modelRepository, never()).save(any());
        verify(routeRepository).save(route);
    }

    /** 空供应商池和空候选模型不应测试全局模型，并应直接同步成功。 */
    @Test
    void syncRouteTreatsEmptyConfigurationAsSuccessfulEmptyRoute() {
        LlmRoute route = route("", "");
        LlmModel matching = model(1L, "MIDDLE");
        LlmModel mismatched = model(2L, "HIGH");
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(matching, mismatched));
        doReturn(Map.of()).when(routeService).testModel(null);

        List<LlmManagementService.ModelHealthView> results = routeService.syncRoute(8L, List.of());

        assertEquals(List.of(), results);
        verify(modelRepository, never()).save(any());
    }

    /** 模型检查失败但未删除时，数据库路由中的模型仍应同步到运行时内存。 */
    @Test
    void failedModelRemainsInActiveRouteUntilProviderIsRemoved() {
        LlmRoute route = route("1", "");
        route.setFeatureCode("summarize");
        route.setEnabled(true);
        LlmModel failed = model(1L, "MIDDLE");
        failed.setModelName("failed-model");
        failed.setSupportedModelTypes(List.of("text_model"));
        ReflectionTestUtils.setField(failed, "id", 10L);
        LlmProvider provider = provider("encrypted");
        ReflectionTestUtils.setField(provider, "id", 1L);
        provider.setEnabled(true);
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(routeRepository.findByFeatureCode("summarize")).thenReturn(Optional.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(failed));
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider));
        when(cryptoService.decrypt("encrypted")).thenReturn("key");
        doThrow(new BusinessException("连接失败")).when(routeService).testModel(10L);

        List<LlmManagementService.ModelHealthView> results = routeService.syncRoute(8L, List.of());

        assertEquals("FAILED", results.get(0).status());
        assertEquals(List.of("failed-model"), routeService.resolveActive("summarize", "text_model").candidates().stream()
            .map(LlmManagementService.WorkerCandidate::model).toList());
    }

    /** 模型配置修改后，运行时路由应保持旧快照，直到再次执行同步。 */
    @Test
    void modelChangesTakeEffectOnlyAfterNextRouteSync() {
        LlmRoute route = route("1", "");
        route.setFeatureCode("summarize");
        route.setEnabled(true);
        LlmModel model = model(1L, "MIDDLE");
        model.setModelName("old-model");
        model.setSupportedModelTypes(List.of("text_model"));
        ReflectionTestUtils.setField(model, "id", 10L);
        LlmProvider provider = provider("encrypted");
        ReflectionTestUtils.setField(provider, "id", 1L);
        provider.setEnabled(true);
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(routeRepository.findByFeatureCode("summarize")).thenReturn(Optional.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(model));
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider));
        when(cryptoService.decrypt("encrypted")).thenReturn("key");
        doReturn(Map.of()).when(routeService).testModel(10L);

        routeService.syncRoute(8L, List.of());
        model.setModelName("new-model");

        assertEquals("old-model", routeService.resolveActive("summarize", "text_model").candidates().get(0).model());

        routeService.syncRoute(8L, List.of());

        assertEquals("new-model", routeService.resolveActive("summarize", "text_model").candidates().get(0).model());
    }

    /** 删除路由模型供应后，应清理数据库关联和内存候选，但保留供应商与模型主数据。 */
    @Test
    void removeProviderUpdatesRouteAndMemoryWithoutDeletingMasterData() {
        LlmRoute route = route("1,2", "");
        route.setFeatureCode("summarize");
        route.setEnabled(true);
        LlmModel first = model(1L, "MIDDLE");
        first.setModelName("first-model");
        first.setSupportedModelTypes(List.of("text_model"));
        ReflectionTestUtils.setField(first, "id", 10L);
        LlmModel second = model(2L, "MIDDLE");
        second.setModelName("second-model");
        second.setSupportedModelTypes(List.of("text_model"));
        ReflectionTestUtils.setField(second, "id", 11L);
        LlmProvider firstProvider = provider("first-encrypted");
        ReflectionTestUtils.setField(firstProvider, "id", 1L);
        LlmProvider secondProvider = provider("second-encrypted");
        ReflectionTestUtils.setField(secondProvider, "id", 2L);
        LlmManagementService routeService = spy(service);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));
        when(routeRepository.findByFeatureCode("summarize")).thenReturn(Optional.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(first, second));
        when(providerRepository.findById(1L)).thenReturn(Optional.of(firstProvider));
        when(providerRepository.findById(2L)).thenReturn(Optional.of(secondProvider));
        when(cryptoService.decrypt("first-encrypted")).thenReturn("first-key");
        when(cryptoService.decrypt("second-encrypted")).thenReturn("second-key");
        doReturn(Map.of()).when(routeService).testModel(anyLong());

        routeService.syncRoute(8L, List.of());
        routeService.removeProviderFromRoute(8L, 1L);

        assertEquals("2", route.getProviderIds());
        assertEquals(List.of("second-model"), routeService.resolveActive("summarize", "text_model").candidates().stream()
            .map(LlmManagementService.WorkerCandidate::model).toList());
        verify(providerRepository, never()).deleteById(anyLong());
        verify(modelRepository, never()).deleteById(anyLong());
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

    /** 空路由不代表全部供应商，删除请求不得把全局供应商反向写入路由。 */
    @Test
    void removeProviderFromEmptyRouteDoesNotExpandProviderPool() {
        LlmRoute route = route("", "");
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(routeRepository.findAll()).thenReturn(List.of(route));

        service.removeProviderFromRoute(8L, 1L);

        assertEquals("", route.getProviderIds());
        assertEquals(false, route.getEnabled());
        verify(providerRepository, never()).findAll();
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
        route.setCapabilityLevel("HIGH");
        route.setEnabled(false);
        when(routeRepository.findByFeatureCode(LlmManagementService.DEFAULT_ROUTE)).thenReturn(Optional.of(route));

        service.ensureDefaultRoute();

        assertEquals(false, route.getEnabled());
        assertEquals("HIGH", route.getCapabilityLevel());
        verify(routeRepository).save(route);
    }

    /** 默认路由保存时应接受用户选择的能力级别，而不是强制重置为中级。 */
    @Test
    void updateDefaultRoutePreservesSelectedCapabilityLevel() {
        LlmRoute route = route("1", "");
        route.setFeatureCode(LlmManagementService.DEFAULT_ROUTE);
        when(routeRepository.findById(8L)).thenReturn(Optional.of(route));
        when(providerRepository.findAllById(List.of(1L))).thenReturn(List.of(mock(LlmProvider.class)));
        when(routeRepository.save(route)).thenReturn(route);

        LlmManagementService.RouteView updated = service.updateRoute(8L, new LlmManagementService.RouteCommand(
            LlmManagementService.DEFAULT_ROUTE, "默认能力路由", List.of(), List.of(1L), "HIGH", false, null, true));

        assertEquals("HIGH", updated.capabilityLevel());
    }

    /** 健康检查可测试不同能力模型，但真实路由候选仍只能包含匹配能力的模型。 */
    @Test
    void routeCandidatesStillFilterByCapabilityLevel() {
        LlmRoute route = route("1", "");
        route.setEnabled(true);
        LlmModel matching = model(1L, "MIDDLE");
        matching.setHealthStatus("HEALTHY");
        matching.setModelName("middle-model");
        LlmModel mismatched = model(1L, "HIGH");
        mismatched.setHealthStatus("HEALTHY");
        mismatched.setModelName("high-model");
        LlmProvider provider = provider("encrypted");
        when(routeRepository.findByFeatureCode("chat")).thenReturn(Optional.of(route));
        when(modelRepository.findAll()).thenReturn(List.of(matching, mismatched));
        when(providerRepository.findById(1L)).thenReturn(Optional.of(provider));
        when(cryptoService.decrypt("encrypted")).thenReturn("key");

        List<LlmManagementService.WorkerCandidate> candidates = service.candidates("chat");

        assertEquals(List.of("middle-model"), candidates.stream().map(LlmManagementService.WorkerCandidate::model).toList());
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
