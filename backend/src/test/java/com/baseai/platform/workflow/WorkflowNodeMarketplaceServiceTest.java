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
    private WorkflowNodeMarketplaceService service;

    /** 创建隔离的市场、包解析和模板持久化替身。 */
    @BeforeEach
    void setUp() {
        clients = mock(WorkflowMarketplaceClients.class);
        packageParser = mock(WorkflowMarketplacePackageParser.class);
        workflowService = mock(WorkflowService.class);
        service = new WorkflowNodeMarketplaceService(clients, packageParser, workflowService, mapper);
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
        assertFalse(page.items().get(1).compatible());
        assertEquals("NO_NATIVE_ADAPTER", page.items().get(1).incompatibilityReason());
    }

    /** 仅兼容筛选必须按白名单统计总数，而不是过滤市场当前页。 */
    @Test
    void pagesCompatibleN8nNodesFromWhitelist() {
        var redis = entry("n8n-nodes-base.redis", "Redis", "", "n8n", "integration");
        when(clients.findN8n(redis.externalId())).thenReturn(Optional.of(redis));

        WorkflowModels.MarketplacePage page = service.nodes("N8N", "redis", "", 1, 20, true);

        assertEquals(1, page.total());
        assertEquals(List.of(redis.externalId()), page.items().stream()
            .map(WorkflowModels.MarketplaceNodeView::externalId).toList());
        assertTrue(page.items().get(0).compatible());
    }

    /** 直接提交未适配市场 ID 必须由服务端再次拒绝。 */
    @Test
    void rejectsUnsupportedDirectImport() {
        var redis = entry("n8n-nodes-base.redis", "Redis", "", "n8n", "integration");
        var slack = entry("n8n-nodes-base.slack", "Slack", "", "n8n", "integration");
        when(clients.findN8n(redis.externalId())).thenReturn(Optional.of(redis));
        when(clients.findN8n(slack.externalId())).thenReturn(Optional.of(slack));

        assertThrows(BusinessException.class, () -> service.importNodes("N8N",
            new WorkflowModels.MarketplaceImportCommand(List.of(redis.externalId(), slack.externalId()), false)));
        verify(workflowService, never()).importMarketplaceTemplates(any(), any(Boolean.class));
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
        return new WorkflowMarketplaceClients.MarketplaceEntry(id, name, "", version, publisher, category,
            "community", mapper.valueToTree(Map.of()));
    }
}
