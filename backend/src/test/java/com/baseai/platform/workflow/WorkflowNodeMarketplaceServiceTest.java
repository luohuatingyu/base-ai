package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
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
    private WorkflowMarketplacePackageParser packageParser;
    private WorkflowService workflowService;
    private WorkflowPluginWorkerClient pluginWorkers;
    private WorkflowPluginRegistryService pluginRegistry;
    private WorkflowNodeMarketplaceService service;

    /** 创建隔离的市场、包解析和模板持久化替身。 */
    @BeforeEach
    void setUp() {
        clients = mock(WorkflowMarketplaceClients.class);
        packageParser = mock(WorkflowMarketplacePackageParser.class);
        workflowService = mock(WorkflowService.class);
        pluginWorkers = mock(WorkflowPluginWorkerClient.class);
        pluginRegistry = mock(WorkflowPluginRegistryService.class);
        service = new WorkflowNodeMarketplaceService(clients, packageParser, workflowService, pluginWorkers, pluginRegistry, mapper);
    }

    /** n8n 全量目录必须同时标记可导入和不兼容节点。 */
    @Test
    void listsAllN8nNodesWithCompatibility() {
        var redis = entry("n8n-nodes-base.redis", "Redis", "", "n8n", "integration");
        var slack = entry("n8n-nodes-base.slack", "Slack", "", "n8n", "integration");
        when(clients.searchN8n("", 1, 20)).thenReturn(
            new WorkflowMarketplaceClients.SearchResult(List.of(redis, slack), 2));

        WorkflowModels.MarketplacePage page = service.nodes("n8n", "", "", 1, 20, false);

        assertEquals(2, page.items().size());
        assertTrue(page.items().get(0).compatible());
        assertEquals("REDIS_COMMAND", page.items().get(0).targetNodeType());
        assertEquals("NATIVE_SUBSET", page.items().get(0).compatibilityLevel());
        assertTrue(page.items().get(1).compatible());
        assertEquals("PROBE_REQUIRED", page.items().get(1).compatibilityLevel());
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

    /** 插件探测没有任何可执行组件时必须拒绝且不创建模板。 */
    @Test
    void rejectsPackageWithoutExecutableComponents() {
        var slack = entry("n8n-nodes-slack.slack", "Slack", "1.0.0", "vendor", "community-node",
            Map.of("packageName", "n8n-nodes-slack"));
        when(clients.findN8n(slack.externalId())).thenReturn(Optional.of(slack));
        when(clients.downloadN8nPackage(slack)).thenReturn(
            new WorkflowMarketplaceClients.PackageDownload(new byte[]{1}, "a".repeat(64)));
        when(pluginWorkers.inspect(any(), any(), any(), any(), any())).thenReturn(workerPackage("N8N",
            "n8n-nodes-slack", "1.0.0", "a".repeat(64), "PARTIAL"));
        when(pluginRegistry.register(any(), any(), any(), any(Boolean.class))).thenReturn(
            new WorkflowPluginRegistryService.Registration(1L, false, List.of(component("PARTIAL"))));

        assertThrows(BusinessException.class, () -> service.importNodes("N8N",
            new WorkflowModels.MarketplaceImportCommand(List.of(slack.externalId()), false)));
        verify(workflowService, never()).importMarketplaceTemplates(any(), any(Boolean.class));
    }

    /** 社区节点包必须经过摘要校验和 ABI 探测后生成固定版本通用模板。 */
    @Test
    void importsProbedN8nPluginComponent() {
        var entry = entry("n8n-nodes-example.action", "Example", "1.2.3", "vendor", "community-node",
            Map.of("packageName", "n8n-nodes-example"));
        String fingerprint = "b".repeat(64);
        when(clients.findN8n(entry.externalId())).thenReturn(Optional.of(entry));
        when(clients.downloadN8nPackage(entry)).thenReturn(
            new WorkflowMarketplaceClients.PackageDownload(new byte[]{1}, fingerprint));
        when(pluginWorkers.inspect(org.mockito.ArgumentMatchers.eq("N8N"),
            org.mockito.ArgumentMatchers.eq("n8n-nodes-example"), org.mockito.ArgumentMatchers.eq("1.2.3"),
            org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.eq(fingerprint)))
            .thenReturn(workerPackage("N8N", "n8n-nodes-example", "1.2.3", fingerprint, "SUPPORTED"));
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
        assertEquals(7L, drafts.getValue().get(0).config().path("pluginComponentId").asLong());
        assertFalse(drafts.getValue().get(0).config().toString().contains("secret"));
        verify(pluginRegistry).setEnabled(3L, true);
    }

    /** Dify 白名单工具必须校验官方包声明后再保存原生 Tavily 模板。 */
    @Test
    void importsValidatedDifyTool() {
        var plugin = entry("langgenius/tavily", "Tavily", "0.1.11", "langgenius", "tool");
        when(clients.findDify("langgenius/tavily")).thenReturn(Optional.of(plugin));
        when(clients.downloadDifyPackage("langgenius/tavily", "0.1.11")).thenReturn(new byte[]{1});
        when(packageParser.requireTool(any(), any(), any(), any())).thenReturn(
            new WorkflowMarketplacePackageParser.ToolDeclaration("tavily_search", "Tavily Search", "Search"));
        when(workflowService.importMarketplaceTemplates(any(), any(Boolean.class))).thenReturn(
            List.of(new WorkflowModels.MarketplaceTemplatePersistence(9L, "CREATED")));

        WorkflowModels.MarketplaceImportResult result = service.importNodes("DIFY",
            new WorkflowModels.MarketplaceImportCommand(List.of("langgenius/tavily/tavily_search"), false));

        assertEquals("CREATED", result.items().get(0).status());
        assertEquals(9L, result.items().get(0).templateId());
        verify(packageParser).requireTool(any(), any(), any(), any());
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

        WorkflowModels.MarketplacePage page = service.nodes("DIFY", "", "", 1, 20, false);

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        assertEquals(List.of("langgenius/tavily/tavily_search", "langgenius/tavily/tavily_extract"),
            page.items().get(0).actions().stream().map(WorkflowModels.MarketplaceActionView::externalId).toList());
        assertEquals("TAVILY_TOOL", page.items().get(0).targetNodeType());
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
        return new WorkflowPluginWorkerClient.WorkerPackage(source, packageId, version, fingerprint, "node",
            List.of(new WorkflowPluginWorkerClient.WorkerComponent("action", "Action", "", "ACTION",
                mapper.createArrayNode(), mapper.createArrayNode(), "node.js", status, "")));
    }

    /** 构造已持久化组件。 */
    private WorkflowPluginRegistryService.RegisteredComponent component(String status) {
        return new WorkflowPluginRegistryService.RegisteredComponent(7L, "action", "ACTION", "Action", "",
            mapper.createArrayNode(), mapper.createArrayNode(), status, "", "c".repeat(64));
    }
}
