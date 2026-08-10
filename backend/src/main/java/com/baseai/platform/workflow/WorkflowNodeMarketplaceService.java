package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 浏览官方节点市场，并只把精确白名单条目转换成 Base AI 原生模板。 */
@Service
public class WorkflowNodeMarketplaceService {
    private static final String DIFY_TAVILY = "langgenius/tavily";
    private static final Map<String, String> N8N_NATIVE_TYPES = Map.of(
        "n8n-nodes-base.postgres", "SQL_QUERY",
        "n8n-nodes-base.mySql", "SQL_QUERY",
        "n8n-nodes-base.redis", "REDIS_COMMAND",
        "n8n-nodes-base.awsS3", "S3_OBJECT",
        "n8n-nodes-base.s3", "S3_OBJECT",
        "n8n-nodes-base.kafkaTrigger", "KAFKA_TRIGGER",
        "n8n-nodes-base.rabbitmqTrigger", "RABBITMQ_TRIGGER"
    );
    private final WorkflowMarketplaceClients clients;
    private final WorkflowMarketplacePackageParser packageParser;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    /** 注入市场客户端、包解析器和模板持久化服务。 */
    public WorkflowNodeMarketplaceService(WorkflowMarketplaceClients clients,
                                          WorkflowMarketplacePackageParser packageParser,
                                          WorkflowService workflowService, ObjectMapper objectMapper) {
        this.clients = clients;
        this.packageParser = packageParser;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    /** 查询指定来源的全量市场目录，并可只保留当前已兼容节点。 */
    public WorkflowModels.MarketplacePage nodes(String rawSource, String query, String category,
                                                int rawPage, int rawPageSize, boolean compatibleOnly) {
        String source = importSource(rawSource);
        int page = Math.max(1, rawPage);
        int pageSize = Math.min(50, Math.max(1, rawPageSize));
        if (compatibleOnly) return compatibleNodes(source, query, category, page, pageSize);
        WorkflowMarketplaceClients.SearchResult result = "N8N".equals(source)
            ? clients.searchN8n(query, page, pageSize) : clients.searchDify(query, category, page, pageSize);
        List<WorkflowModels.MarketplaceNodeView> items = new ArrayList<>();
        for (WorkflowMarketplaceClients.MarketplaceEntry entry : result.items()) {
            if ("N8N".equals(source)) items.add(view(entry, adaptN8n(entry).orElse(null)));
            else if (DIFY_TAVILY.equals(entry.externalId())) items.add(difyPluginView(entry));
            else items.add(view(entry, null));
        }
        return new WorkflowModels.MarketplacePage(source, List.copyOf(items), page, pageSize, result.total());
    }

    /** 从服务端白名单先生成兼容目录，再执行检索和稳定分页。 */
    private WorkflowModels.MarketplacePage compatibleNodes(String source, String query, String category,
                                                            int page, int pageSize) {
        List<WorkflowModels.MarketplaceNodeView> items = new ArrayList<>();
        if ("N8N".equals(source)) {
            N8N_NATIVE_TYPES.keySet().stream().sorted().map(clients::findN8n).flatMap(Optional::stream)
                .map(entry -> view(entry, adaptN8n(entry).orElseThrow())).forEach(items::add);
        } else if (category == null || category.isBlank() || "tool".equalsIgnoreCase(category)) {
            clients.findDify(DIFY_TAVILY).map(this::difyPluginView).ifPresent(items::add);
        }
        String needle = normalized(query);
        List<WorkflowModels.MarketplaceNodeView> filtered = items.stream()
            .filter(item -> matches(item, needle)).toList();
        int from = Math.min(filtered.size(), (page - 1) * pageSize);
        int to = Math.min(filtered.size(), from + pageSize);
        return new WorkflowModels.MarketplacePage(source, List.copyOf(filtered.subList(from, to)),
            page, pageSize, filtered.size());
    }

    /** 逐项重新确认市场条目和适配器，并返回幂等导入结果。 */
    public WorkflowModels.MarketplaceImportResult importNodes(String rawSource,
                                                               WorkflowModels.MarketplaceImportCommand command) {
        String source = importSource(rawSource);
        if (command == null || command.externalIds() == null || command.externalIds().isEmpty()) {
            throw new BusinessException("workflow.marketplaceNodeNotFound");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(command.externalIds());
        if (ids.size() > 50 || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 255)) {
            throw new BusinessException("workflow.marketplaceNodeNotFound");
        }
        Map<String, byte[]> packageCache = new LinkedHashMap<>();
        List<WorkflowModels.MarketplaceTemplateDraft> drafts = new ArrayList<>();
        for (String externalId : ids) {
            drafts.add("N8N".equals(source) ? requireN8n(externalId) : requireDify(externalId, packageCache));
        }
        List<WorkflowModels.MarketplaceTemplatePersistence> persisted = workflowService.importMarketplaceTemplates(
            List.copyOf(drafts), Boolean.TRUE.equals(command.replaceExisting()));
        List<WorkflowModels.MarketplaceImportItem> imported = new ArrayList<>();
        int index = 0;
        for (String externalId : ids) {
            WorkflowModels.MarketplaceTemplatePersistence result = persisted.get(index++);
            imported.add(new WorkflowModels.MarketplaceImportItem(externalId, result.status(), result.templateId()));
        }
        return new WorkflowModels.MarketplaceImportResult(source, List.copyOf(imported));
    }

    /** 重新读取 n8n 条目并拒绝未进入精确映射表的节点。 */
    private WorkflowModels.MarketplaceTemplateDraft requireN8n(String externalId) {
        WorkflowMarketplaceClients.MarketplaceEntry entry = clients.findN8n(externalId)
            .orElseThrow(() -> new BusinessException("workflow.marketplaceNodeNotFound"));
        NativeDraft nativeDraft = adaptN8n(entry)
            .orElseThrow(() -> new BusinessException("workflow.marketplaceNodeUnsupported"));
        return draft("N8N", entry, externalId, nativeDraft);
    }

    /** 只接受已登记的 Tavily 工具，并通过官方插件包声明再次确认工具身份。 */
    private WorkflowModels.MarketplaceTemplateDraft requireDify(String externalId, Map<String, byte[]> packageCache) {
        int separator = externalId.lastIndexOf('/');
        if (separator <= 0) throw new BusinessException("workflow.marketplaceNodeUnsupported");
        String pluginId = externalId.substring(0, separator);
        String toolName = externalId.substring(separator + 1);
        if (!DIFY_TAVILY.equals(pluginId) || !List.of("tavily_search", "tavily_extract").contains(toolName)) {
            throw new BusinessException("workflow.marketplaceNodeUnsupported");
        }
        WorkflowMarketplaceClients.MarketplaceEntry plugin = clients.findDify(pluginId)
            .orElseThrow(() -> new BusinessException("workflow.marketplaceNodeNotFound"));
        byte[] archive = packageCache.computeIfAbsent(pluginId + "@" + plugin.version(),
            key -> clients.downloadDifyPackage(pluginId, plugin.version()));
        WorkflowMarketplacePackageParser.ToolDeclaration declaration = packageParser.requireTool(archive,
            "provider/tavily.yaml", "tools/" + toolName + ".yaml", toolName);
        NativeDraft nativeDraft = difyTool(plugin, toolName, declaration.label(), declaration.description());
        return draft("DIFY", plugin, externalId, nativeDraft);
    }

    /** 把 n8n 数据连接类节点映射为现有原生连接执行器。 */
    private Optional<NativeDraft> adaptN8n(WorkflowMarketplaceClients.MarketplaceEntry entry) {
        String nodeType = N8N_NATIVE_TYPES.get(entry.externalId());
        if (nodeType == null) return Optional.empty();
        return Optional.of(new NativeDraft(entry.externalId(), entry.name(), entry.description(), nodeType,
            WorkflowTemplateCatalog.defaultCategory(nodeType), objectMapper.createObjectNode()));
    }

    /** 为 Dify Tavily 工具生成只引用加密连接的原生配置，不保存或执行插件 Python。 */
    private NativeDraft difyTool(WorkflowMarketplaceClients.MarketplaceEntry plugin, String toolName,
                                 String name, String description) {
        ObjectNode config = objectMapper.createObjectNode();
        config.putNull("connectionId");
        if ("tavily_search".equals(toolName)) {
            config.put("operation", "SEARCH").put("query", "{{input.query}}")
                .put("searchDepth", "basic").put("maxResults", 5);
        } else {
            config.put("operation", "EXTRACT").put("urls", "{{input.urls}}")
                .put("extractDepth", "basic").put("format", "markdown");
        }
        return new NativeDraft(plugin.externalId() + "/" + toolName, name, description,
            "TAVILY_TOOL", "NETWORK_API", config);
    }

    /** 构造统一市场卡片；未兼容项只返回稳定原因码。 */
    private WorkflowModels.MarketplaceNodeView view(WorkflowMarketplaceClients.MarketplaceEntry entry,
                                                     NativeDraft draft) {
        return draft == null
            ? new WorkflowModels.MarketplaceNodeView(entry.externalId(), entry.name(), entry.description(),
                entry.version(), entry.publisher(), entry.category(), false, "NO_NATIVE_ADAPTER", "", "",
                "NONE", List.of())
            : view(draft, entry.version(), entry.publisher(), entry.category());
    }

    /** 构造兼容市场节点卡片。 */
    private WorkflowModels.MarketplaceNodeView view(NativeDraft draft, String version, String publisher, String category) {
        return new WorkflowModels.MarketplaceNodeView(draft.externalId(), draft.name(), draft.description(), version,
            publisher, category, true, "", draft.nodeType(), draft.functionalCategory(), "NATIVE_SUBSET", List.of());
    }

    /** 以插件为分页单位展示 Dify，并把受支持工具作为可选择动作返回。 */
    private WorkflowModels.MarketplaceNodeView difyPluginView(WorkflowMarketplaceClients.MarketplaceEntry plugin) {
        List<WorkflowModels.MarketplaceActionView> actions = List.of(
            action(difyTool(plugin, "tavily_search", "Tavily Search", "Search the web with Tavily")),
            action(difyTool(plugin, "tavily_extract", "Tavily Extract", "Extract content from web pages with Tavily"))
        );
        return new WorkflowModels.MarketplaceNodeView(plugin.externalId(), plugin.name(), plugin.description(),
            plugin.version(), plugin.publisher(), plugin.category(), true, "", "TAVILY_TOOL", "NETWORK_API",
            "NATIVE_SUBSET", actions);
    }

    /** 把单个原生适配动作转换为市场子项。 */
    private WorkflowModels.MarketplaceActionView action(NativeDraft draft) {
        return new WorkflowModels.MarketplaceActionView(draft.externalId(), draft.name(), draft.description(), true,
            "", draft.nodeType(), draft.functionalCategory(), "NATIVE_SUBSET");
    }

    /** 搜索同时匹配插件身份和受支持动作。 */
    private boolean matches(WorkflowModels.MarketplaceNodeView item, String needle) {
        return needle.isBlank() || normalized(item.name()).contains(needle)
            || normalized(item.externalId()).contains(needle)
            || item.actions().stream().anyMatch(action -> normalized(action.name()).contains(needle)
                || normalized(action.externalId()).contains(needle));
    }

    /** 把市场元数据和原生转换结果固化为不可伪造的持久化命令。 */
    private WorkflowModels.MarketplaceTemplateDraft draft(String source,
                                                           WorkflowMarketplaceClients.MarketplaceEntry entry,
                                                           String externalId, NativeDraft nativeDraft) {
        String fingerprint = sha256(source + "\n" + externalId + "\n" + entry.version() + "\n"
            + nativeDraft.nodeType() + "\n" + nativeDraft.config());
        return new WorkflowModels.MarketplaceTemplateDraft(source, externalId, entry.version(), entry.publisher(),
            fingerprint, source + "_" + sha256(externalId).substring(0, 16).toUpperCase(Locale.ROOT),
            nativeDraft.name(), nativeDraft.description(), nativeDraft.nodeType(), nativeDraft.functionalCategory(),
            nativeDraft.config().deepCopy());
    }

    /** 仅允许两种外部市场来源。 */
    private String importSource(String rawSource) {
        String source = rawSource == null ? "" : rawSource.trim().toUpperCase(Locale.ROOT);
        if (!List.of("N8N", "DIFY").contains(source)) throw new BusinessException("workflow.marketplaceSourceInvalid");
        return source;
    }

    /** 计算稳定 SHA-256 标识。 */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 生成大小写无关的市场搜索文本。 */
    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record NativeDraft(String externalId, String name, String description, String nodeType,
                               String functionalCategory, JsonNode config) {}
}
