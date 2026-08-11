package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class WorkflowNodeMarketplaceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private WorkflowMarketplaceClients clients;
    private WorkflowService workflowService;
    private WorkflowPluginProbeService pluginProbes;
    private WorkflowPluginRegistryService pluginRegistry;
    private WorkflowNodeMarketplaceService service;

    /** 创建隔离的市场、包解析和模板持久化替身。 */
    @BeforeEach
    void setUp() {
        clients = mock(WorkflowMarketplaceClients.class);
        workflowService = mock(WorkflowService.class);
        pluginProbes = mock(WorkflowPluginProbeService.class);
        pluginRegistry = mock(WorkflowPluginRegistryService.class);
        service = new WorkflowNodeMarketplaceService(clients, workflowService, pluginProbes, pluginRegistry, mapper);
        AuthContext.set(new AuthUser(1L, "admin", java.util.Set.of("ADMIN"), java.util.Set.of(),
            AuthenticationType.TOKEN, null, null));
    }

    /** 清理当前线程测试身份，避免权限泄漏到其他用例。 */
    @org.junit.jupiter.api.AfterEach
    void tearDown() { AuthContext.clear(); }

    /** n8n 全量目录必须同时标记可导入和不兼容节点。 */
    @Test
    void listsAllN8nNodesWithCompatibility() {
        var redis = entry("n8n-nodes-base.redis", "Redis", "", "n8n", "integration");
        var slack = entry("n8n-nodes-base.slack", "Slack", "", "n8n", "integration");
        when(clients.searchN8n("", 1, 20)).thenReturn(
            new WorkflowMarketplaceClients.SearchResult(List.of(redis, slack), 2));
        when(pluginProbes.snapshot("N8N", slack, true)).thenReturn(
            new WorkflowPluginProbeService.ProbeSnapshot("QUEUED", "PROBING", "", null));
        when(workflowService.activeMarketplaceTemplateFingerprints("N8N")).thenReturn(Map.of(
            redis.externalId(), sha256("N8N\n" + redis.externalId() + "\n\nREDIS_COMMAND\n{}")));

        WorkflowModels.MarketplacePage page = service.nodes("n8n", "", "", 1, 20, false);

        assertEquals(2, page.items().size());
        assertTrue(page.items().get(0).compatible());
        assertEquals("REDIS_COMMAND", page.items().get(0).targetNodeType());
        assertEquals("NATIVE_SUBSET", page.items().get(0).compatibilityLevel());
        assertTrue(page.items().get(0).imported());
        assertFalse(page.items().get(1).compatible());
        assertEquals("QUEUED", page.items().get(1).probeStatus());
        verify(pluginProbes).snapshot("N8N", slack, true);
    }

    /** 仅兼容筛选必须按白名单统计总数，而不是过滤市场当前页。 */
    @Test
    void pagesCompatibleN8nNodesFromWhitelist() {
        var redis = entry("n8n-nodes-base.redis", "Redis", "", "n8n", "integration");
        when(clients.searchN8n("redis", 1, 20)).thenReturn(
            new WorkflowMarketplaceClients.SearchResult(List.of(redis), 1));

        WorkflowModels.MarketplacePage page = service.nodes("N8N", "redis", "", 1, 20, true);

        assertEquals(1, page.total());
        assertEquals(List.of(redis.externalId()), page.items().stream()
            .map(WorkflowModels.MarketplaceNodeView::externalId).toList());
        assertTrue(page.items().get(0).compatible());
    }

    /** 只有导入权限可以通过只读市场接口产生新的后台探测任务。 */
    @Test
    void listOnlyPermissionDoesNotEnqueuePluginProbe() {
        AuthContext.set(new AuthUser(2L, "reader", java.util.Set.of("USER"),
            java.util.Set.of("workflow:node:list"), AuthenticationType.TOKEN, null, null));
        var plugin = entry("n8n-nodes-example.action", "Example", "1.0.0", "vendor", "community-node",
            Map.of("packageName", "n8n-nodes-example"));
        when(clients.searchN8n("", 1, 20)).thenReturn(
            new WorkflowMarketplaceClients.SearchResult(List.of(plugin), 1));
        when(pluginProbes.snapshot("N8N", plugin, false)).thenReturn(
            new WorkflowPluginProbeService.ProbeSnapshot("NOT_PROBED", "PROBING", "", null));

        WorkflowModels.MarketplacePage page = service.nodes("N8N", "", "", 1, 20, false);

        assertFalse(page.items().get(0).compatible());
        assertTrue(page.probePending());
        verify(pluginProbes).snapshot("N8N", plugin, false);
    }

    /** 已完成探测的安全原因码应传给市场页面，未知内部细节仍使用通用提示。 */
    @Test
    void exposesOnlyStablePluginProbeReason() {
        var plugin = entry("n8n-nodes-example.action", "Example", "1.0.0", "vendor", "community-node",
            Map.of("packageName", "n8n-nodes-example"));
        when(clients.searchN8n("", 1, 20)).thenReturn(
            new WorkflowMarketplaceClients.SearchResult(List.of(plugin), 1));
        when(pluginProbes.snapshot("N8N", plugin, true)).thenReturn(
            new WorkflowPluginProbeService.ProbeSnapshot("COMPLETE", "UNSUPPORTED", "ROUTING_UNSUPPORTED", null));

        assertEquals("ROUTING_UNSUPPORTED",
            service.nodes("N8N", "", "", 1, 20, false).items().get(0).incompatibilityReason());

        when(pluginProbes.snapshot("N8N", plugin, true)).thenReturn(
            new WorkflowPluginProbeService.ProbeSnapshot("REJECTED", "UNSUPPORTED", "/private/path", null));
        assertEquals("PACKAGE_REJECTED",
            service.nodes("N8N", "", "", 1, 20, false).items().get(0).incompatibilityReason());
    }

    /** 市场预览必须按照组件实际语义展示分类，而不是把所有动作归入网络接口。 */
    @Test
    void previewsPluginCategoryFromComponentCapability() {
        var slack = entry("n8n-nodes-slack.send", "Slack", "1.0.0", "vendor", "community-node",
            Map.of("packageName", "n8n-nodes-slack"));
        when(clients.searchN8n("", 1, 20)).thenReturn(
            new WorkflowMarketplaceClients.SearchResult(List.of(slack), 1));
        when(pluginProbes.snapshot("N8N", slack, true)).thenReturn(new WorkflowPluginProbeService.ProbeSnapshot(
            "COMPLETE", "SUPPORTED", "", workerPackage("N8N", "n8n-nodes-slack", "1.0.0",
            "f".repeat(64), "SUPPORTED", "send_slack_message")));

        WorkflowModels.MarketplaceNodeView item = service.nodes("N8N", "", "", 1, 20, false).items().get(0);

        assertTrue(item.compatible());
        assertEquals("NOTIFICATION", item.functionalCategory());
    }

    /** 插件探测没有任何可执行组件时必须拒绝且不创建模板。 */
    @Test
    void rejectsPackageWithoutExecutableComponents() {
        var slack = entry("n8n-nodes-slack.slack", "Slack", "1.0.0", "vendor", "community-node",
            Map.of("packageName", "n8n-nodes-slack"));
        when(clients.findN8n(slack.externalId())).thenReturn(Optional.of(slack));
        when(pluginProbes.requireCompleted("N8N", slack)).thenReturn(workerPackage("N8N",
            "n8n-nodes-slack", "1.0.0", "a".repeat(64), "PARTIAL"));
        when(pluginRegistry.register(any(), any(), any(), any(Boolean.class))).thenReturn(
            new WorkflowPluginRegistryService.Registration(1L, false, List.of(component("PARTIAL"))));

        assertThrows(BusinessException.class, () -> service.importNodes("N8N",
            new WorkflowModels.MarketplaceImportCommand(List.of(slack.externalId()), false)));
        verify(workflowService, never()).importMarketplaceTemplates(any(), any(Boolean.class));
    }

    /** 社区节点导入必须只消费既有探测结果并生成固定版本通用模板。 */
    @Test
    void importsProbedN8nPluginComponent() {
        var entry = entry("n8n-nodes-example.action", "Example", "1.2.3", "vendor", "community-node",
            Map.of("packageName", "n8n-nodes-example"));
        String fingerprint = "b".repeat(64);
        when(clients.findN8n(entry.externalId())).thenReturn(Optional.of(entry));
        when(pluginProbes.requireCompleted("N8N", entry)).thenReturn(
            workerPackage("N8N", "n8n-nodes-example", "1.2.3", fingerprint, "SUPPORTED"));
        when(pluginRegistry.register(any(), any(), any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(
            new WorkflowPluginRegistryService.Registration(3L, false, List.of(component("SUPPORTED"))));
        when(workflowService.importMarketplaceTemplates(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(
            List.of(new WorkflowModels.MarketplaceTemplatePersistence(12L, "CREATED")));

        WorkflowModels.MarketplaceImportResult result = service.importNodes("N8N",
            new WorkflowModels.MarketplaceImportCommand(List.of(entry.externalId()), false));

        assertEquals("CREATED", result.items().get(0).status());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkflowModels.MarketplaceTemplateDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(workflowService).importMarketplaceTemplates(drafts.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertEquals("PLUGIN_ACTION", drafts.getValue().get(0).nodeType());
        assertEquals("BASIC", drafts.getValue().get(0).functionalCategory());
        assertEquals(7L, drafts.getValue().get(0).config().path("pluginComponentId").asLong());
        assertFalse(drafts.getValue().get(0).config().toString().contains("secret"));
        verify(pluginRegistry).setEnabled(3L, true);
        verify(clients, never()).downloadN8nPackage(any());
    }

    /** Dify 白名单工具必须校验官方包声明后再保存原生 Tavily 模板。 */
    @Test
    void importsValidatedDifyTool() {
        var plugin = entry("langgenius/tavily", "Tavily", "0.1.11", "langgenius", "tool");
        when(clients.findDify("langgenius/tavily")).thenReturn(Optional.of(plugin));
        when(pluginProbes.requireCompleted("DIFY", plugin)).thenReturn(workerPackage("DIFY", "langgenius/tavily",
            "0.1.11", "d".repeat(64), "SUPPORTED", "tavily_search"));
        when(workflowService.importMarketplaceTemplates(any(), any(Boolean.class))).thenReturn(
            List.of(new WorkflowModels.MarketplaceTemplatePersistence(9L, "CREATED")));

        WorkflowModels.MarketplaceImportResult result = service.importNodes("DIFY",
            new WorkflowModels.MarketplaceImportCommand(List.of("langgenius/tavily/tavily_search"), false));

        assertEquals("CREATED", result.items().get(0).status());
        assertEquals(9L, result.items().get(0).templateId());
        verify(clients, never()).downloadDifyPackage(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkflowModels.MarketplaceTemplateDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(workflowService).importMarketplaceTemplates(drafts.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertEquals("TAVILY_TOOL", drafts.getValue().get(0).nodeType());
        assertFalse(drafts.getValue().get(0).config().has("apiKey"));
        assertFalse(drafts.getValue().get(0).config().toString().contains("api_key"));
    }

    /** Dify 分页必须以插件为单位，并在插件内返回可选择工具动作。 */
    @Test
    void keepsDifyPluginPaginationStableWithChildActions() {
        var plugin = entry("langgenius/tavily", "Tavily", "0.1.11", "langgenius", "tool");
        when(clients.searchDify("", "", 1, 20)).thenReturn(
            new WorkflowMarketplaceClients.SearchResult(List.of(plugin), 1));
        when(pluginProbes.snapshot("DIFY", plugin, true)).thenReturn(new WorkflowPluginProbeService.ProbeSnapshot(
            "COMPLETE", "SUPPORTED", "", workerPackage("DIFY", "langgenius/tavily", "0.1.11",
            "e".repeat(64), "SUPPORTED", "tavily_search", "tavily_extract")));

        WorkflowModels.MarketplacePage page = service.nodes("DIFY", "", "", 1, 20, false);

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        assertEquals(List.of("langgenius/tavily/tavily_search", "langgenius/tavily/tavily_extract"),
            page.items().get(0).actions().stream().map(WorkflowModels.MarketplaceActionView::externalId).toList());
        assertEquals("TAVILY_TOOL", page.items().get(0).targetNodeType());
    }

    /** 只有当前插件全部受支持组件均存在且指纹一致时，市场卡片才算完整导入。 */
    @Test
    void marksMultiComponentPluginImportedOnlyWhenEveryCurrentTemplateExists() {
        var plugin = entry("n8n-nodes-example.action", "Example", "1.2.3", "vendor", "community-node",
            Map.of("packageName", "n8n-nodes-example"));
        String packageFingerprint = "f".repeat(64);
        var inspected = workerPackage("N8N", "n8n-nodes-example", "1.2.3", packageFingerprint,
            "SUPPORTED", "action", "trigger");
        when(clients.searchN8n("", 1, 20)).thenReturn(
            new WorkflowMarketplaceClients.SearchResult(List.of(plugin), 1));
        when(pluginProbes.snapshot("N8N", plugin, true)).thenReturn(
            new WorkflowPluginProbeService.ProbeSnapshot("COMPLETE", "SUPPORTED", "", inspected));
        String actionFingerprint = pluginTemplateFingerprint(packageFingerprint, "ACTION");
        when(workflowService.activeMarketplaceTemplateFingerprints("N8N")).thenReturn(Map.of(
            "n8n-nodes-example/action", actionFingerprint,
            "n8n-nodes-example/trigger", actionFingerprint));

        assertTrue(service.nodes("N8N", "", "", 1, 20, false).items().get(0).imported());

        when(workflowService.activeMarketplaceTemplateFingerprints("N8N")).thenReturn(Map.of(
            "n8n-nodes-example/action", actionFingerprint));
        assertFalse(service.nodes("N8N", "", "", 1, 20, false).items().get(0).imported());

        when(workflowService.activeMarketplaceTemplateFingerprints("N8N")).thenReturn(Map.of(
            "n8n-nodes-example/action", actionFingerprint,
            "n8n-nodes-example/trigger", "0".repeat(64)));
        assertFalse(service.nodes("N8N", "", "", 1, 20, false).items().get(0).imported());
    }

    /** Dify 子能力分别标记导入状态，父插件只在全部兼容能力均导入时聚合为已导入。 */
    @Test
    void aggregatesImportedStateForDifyActions() {
        var plugin = entry("langgenius/tavily", "Tavily", "0.1.11", "langgenius", "tool");
        String packageFingerprint = "e".repeat(64);
        var inspected = workerPackage("DIFY", "langgenius/tavily", "0.1.11", packageFingerprint,
            "SUPPORTED", "tavily_search", "tavily_extract");
        when(clients.searchDify("", "", 1, 20)).thenReturn(
            new WorkflowMarketplaceClients.SearchResult(List.of(plugin), 1));
        when(pluginProbes.snapshot("DIFY", plugin, true)).thenReturn(new WorkflowPluginProbeService.ProbeSnapshot(
            "COMPLETE", "SUPPORTED", "", inspected));
        String searchFingerprint = sha256(packageFingerprint + "\n" + "tavily_search" + "\n[]");
        String extractFingerprint = sha256(packageFingerprint + "\n" + "tavily_extract" + "\n[]");
        when(workflowService.activeMarketplaceTemplateFingerprints("DIFY")).thenReturn(Map.of(
            "langgenius/tavily/tavily_search", searchFingerprint));

        WorkflowModels.MarketplaceNodeView partial = service.nodes("DIFY", "", "", 1, 20, false).items().get(0);
        assertTrue(partial.actions().get(0).imported());
        assertFalse(partial.actions().get(1).imported());
        assertFalse(partial.imported());

        when(workflowService.activeMarketplaceTemplateFingerprints("DIFY")).thenReturn(Map.of(
            "langgenius/tavily/tavily_search", searchFingerprint,
            "langgenius/tavily/tavily_extract", extractFingerprint));
        assertTrue(service.nodes("DIFY", "", "", 1, 20, false).items().get(0).imported());
    }

    /** 构造最小市场条目。 */
    private WorkflowMarketplaceClients.MarketplaceEntry entry(String id, String name, String version,
                                                               String publisher, String category) {
        return entry(id, name, version, publisher, category, Map.of());
    }

    /** 构造带指定原始字段的市场条目。 */
    private WorkflowMarketplaceClients.MarketplaceEntry entry(String id, String name, String version,
                                                               String publisher, String category, Map<String, Object> raw) {
        return new WorkflowMarketplaceClients.MarketplaceEntry(id, name, "", version, publisher, category,
            "community", mapper.valueToTree(raw));
    }

    /** 构造单组件 Worker 探测结果。 */
    private WorkflowPluginWorkerClient.WorkerPackage workerPackage(String source, String packageId, String version,
                                                                   String fingerprint, String status) {
        return workerPackage(source, packageId, version, fingerprint, status, "action");
    }

    /** 构造包含指定组件身份的 Worker 探测结果。 */
    private WorkflowPluginWorkerClient.WorkerPackage workerPackage(String source, String packageId, String version,
                                                                   String fingerprint, String status,
                                                                   String... componentIds) {
        return new WorkflowPluginWorkerClient.WorkerPackage(source, packageId, version, fingerprint, "node",
            java.util.Arrays.stream(componentIds).map(componentId -> new WorkflowPluginWorkerClient.WorkerComponent(
                componentId, componentId, "", "ACTION", mapper.createArrayNode(), mapper.createArrayNode(),
                "node.js", status, "")).toList());
    }

    /** 构造已持久化组件。 */
    private WorkflowPluginRegistryService.RegisteredComponent component(String status) {
        return new WorkflowPluginRegistryService.RegisteredComponent(7L, "action", "ACTION", "Action", "",
            mapper.createArrayNode(), mapper.createArrayNode(), status, "", "c".repeat(64));
    }

    /** 按注册表和模板服务的现有算法计算通用插件模板指纹。 */
    private String pluginTemplateFingerprint(String packageFingerprint, String componentType) {
        return sha256(packageFingerprint + "\n" + sha256(componentType + "\n[]\n[]"));
    }

    /** 计算测试数据使用的稳定 SHA-256。 */
    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
