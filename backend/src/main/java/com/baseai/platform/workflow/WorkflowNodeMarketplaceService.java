package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final WorkflowPluginWorkerClient pluginWorkers;
    private final WorkflowPluginRegistryService pluginRegistry;
    private final ObjectMapper objectMapper;

    /** 注入市场客户端、包解析器和模板持久化服务。 */
    public WorkflowNodeMarketplaceService(WorkflowMarketplaceClients clients,
                                          WorkflowMarketplacePackageParser packageParser,
                                          WorkflowService workflowService, WorkflowPluginWorkerClient pluginWorkers,
                                          WorkflowPluginRegistryService pluginRegistry, ObjectMapper objectMapper) {
        this.clients = clients;
        this.packageParser = packageParser;
        this.workflowService = workflowService;
        this.pluginWorkers = pluginWorkers;
        this.pluginRegistry = pluginRegistry;
        this.objectMapper = objectMapper;
    }

    /** 查询指定来源的全量市场目录，并可只保留当前已兼容节点。 */
    public WorkflowModels.MarketplacePage nodes(String rawSource, String query, String category,
                                                int rawPage, int rawPageSize, boolean compatibleOnly) {
        String source = importSource(rawSource);
        int page = Math.max(1, rawPage);
        int pageSize = Math.min(50, Math.max(1, rawPageSize));
        WorkflowMarketplaceClients.SearchResult result = "N8N".equals(source)
            ? clients.searchN8n(query, page, pageSize) : clients.searchDify(query, category, page, pageSize);
        List<WorkflowModels.MarketplaceNodeView> items = new ArrayList<>();
        for (WorkflowMarketplaceClients.MarketplaceEntry entry : result.items()) {
            if ("N8N".equals(source)) items.add(adaptN8n(entry).map(draft -> view(entry, draft))
                .orElseGet(() -> probeView(entry, "PLUGIN_ACTION")));
            else if (DIFY_TAVILY.equals(entry.externalId())) items.add(difyPluginView(entry));
            else items.add(probeView(entry, pluginNodeType(entry.category())));
        }
        List<WorkflowModels.MarketplaceNodeView> filtered = compatibleOnly
            ? items.stream().filter(WorkflowModels.MarketplaceNodeView::compatible).toList() : items;
        return new WorkflowModels.MarketplacePage(source, List.copyOf(filtered), page, pageSize,
            compatibleOnly ? filtered.size() : result.total());
    }

    /** 逐项重新确认市场条目和适配器，并返回幂等导入结果。 */
    @Transactional
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
        List<PreparedImport> prepared = new ArrayList<>();
        for (String externalId : ids) {
            if ("N8N".equals(source)) {
                WorkflowMarketplaceClients.MarketplaceEntry entry = clients.findN8n(externalId)
                    .orElseThrow(() -> new BusinessException("workflow.marketplaceNodeNotFound"));
                Optional<NativeDraft> nativeDraft = adaptN8n(entry);
                if (nativeDraft.isPresent()) {
                    WorkflowModels.MarketplaceTemplateDraft draft = draft("N8N", entry, externalId, nativeDraft.get());
                    drafts.add(draft);
                    prepared.add(new PreparedImport(externalId, draft, null));
                } else preparePlugin(source, externalId, entry, Boolean.TRUE.equals(command.replaceExisting()), drafts, prepared);
            } else if (externalId.startsWith(DIFY_TAVILY + "/")) {
                WorkflowModels.MarketplaceTemplateDraft draft = requireDify(externalId, packageCache);
                drafts.add(draft);
                prepared.add(new PreparedImport(externalId, draft, null));
            } else {
                WorkflowMarketplaceClients.MarketplaceEntry entry = clients.findDify(externalId)
                    .orElseThrow(() -> new BusinessException("workflow.marketplaceNodeNotFound"));
                preparePlugin(source, externalId, entry, Boolean.TRUE.equals(command.replaceExisting()), drafts, prepared);
            }
        }
        List<WorkflowModels.MarketplaceTemplatePersistence> persisted = drafts.isEmpty() ? List.of()
            : workflowService.importMarketplaceTemplates(List.copyOf(drafts), Boolean.TRUE.equals(command.replaceExisting()));
        List<WorkflowModels.MarketplaceImportItem> imported = new ArrayList<>();
        int index = 0;
        for (PreparedImport item : prepared) {
            if (item.status() != null) {
                imported.add(new WorkflowModels.MarketplaceImportItem(item.requestExternalId(), item.status(), null));
            } else {
                WorkflowModels.MarketplaceTemplatePersistence result = persisted.get(index++);
                imported.add(new WorkflowModels.MarketplaceImportItem(item.requestExternalId(), result.status(), result.templateId()));
            }
        }
        return new WorkflowModels.MarketplaceImportResult(source, List.copyOf(imported));
    }

    /** 返回已安装且通过 ABI 探测的非敏感插件组件。 */
    public List<WorkflowModels.PluginComponentOption> componentOptions() {
        return pluginRegistry.componentOptions();
    }

    /** 下载并探测插件包，把可执行组件转换为固定版本模板。 */
    private void preparePlugin(String source, String requestExternalId,
                               WorkflowMarketplaceClients.MarketplaceEntry catalogEntry, boolean replaceExisting,
                               List<WorkflowModels.MarketplaceTemplateDraft> drafts, List<PreparedImport> prepared) {
        WorkflowMarketplaceClients.MarketplaceEntry packageEntry = catalogEntry;
        byte[] archive;
        String fingerprint;
        String packageId;
        if ("N8N".equals(source)) {
            packageId = catalogEntry.raw().path("packageName").asText("");
            if (packageId.isBlank()) throw new BusinessException("workflow.marketplacePackageInvalid");
            WorkflowMarketplaceClients.PackageDownload download = clients.downloadN8nPackage(catalogEntry);
            archive = download.bytes();
            fingerprint = download.fingerprint();
            packageEntry = new WorkflowMarketplaceClients.MarketplaceEntry(packageId, catalogEntry.name(),
                catalogEntry.description(), catalogEntry.version(), catalogEntry.publisher(), catalogEntry.category(),
                catalogEntry.trustLevel(), catalogEntry.raw());
        } else {
            packageId = catalogEntry.externalId();
            archive = clients.downloadDifyPackage(packageId, catalogEntry.version());
            fingerprint = sha256(archive);
        }
        WorkflowPluginWorkerClient.WorkerPackage inspected = pluginWorkers.inspect(source, packageId,
            catalogEntry.version(), archive, fingerprint);
        WorkflowPluginRegistryService.Registration registration = pluginRegistry.register(source, packageEntry,
            inspected, replaceExisting);
        if (registration.updateAvailable()) {
            prepared.add(new PreparedImport(requestExternalId, null, "UPDATE_AVAILABLE"));
            return;
        }
        boolean supported = false;
        for (WorkflowPluginRegistryService.RegisteredComponent component : registration.components()) {
            if (!"SUPPORTED".equals(component.compatibilityStatus())) {
                prepared.add(new PreparedImport(requestExternalId, null, component.compatibilityStatus()));
                continue;
            }
            supported = true;
            WorkflowModels.MarketplaceTemplateDraft draft = pluginDraft(source, packageEntry, fingerprint, component);
            drafts.add(draft);
            prepared.add(new PreparedImport(requestExternalId, draft, null));
        }
        if (!supported) throw new BusinessException("workflow.marketplaceNodeUnsupported");
        pluginRegistry.setEnabled(registration.pluginId(), true);
    }

    /** 把已持久化插件组件转换为通用工作流模板。 */
    private WorkflowModels.MarketplaceTemplateDraft pluginDraft(String source,
                                                                 WorkflowMarketplaceClients.MarketplaceEntry entry,
                                                                 String packageFingerprint,
                                                                 WorkflowPluginRegistryService.RegisteredComponent component) {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("pluginComponentId", component.id()).put("packageFingerprint", packageFingerprint)
            .put("componentExternalId", component.externalKey()).put("componentType", component.componentType());
        config.putNull("connectionId");
        config.set("parameters", defaults(component.schema()));
        config.set("parameterSchema", component.schema());
        config.set("credentialSchema", component.credentialSchema());
        String externalKey = entry.externalId() + "/" + component.externalKey();
        String fingerprint = sha256(packageFingerprint + "\n" + component.schemaFingerprint());
        String nodeType = pluginNodeType(component.componentType());
        return new WorkflowModels.MarketplaceTemplateDraft(source, externalKey, entry.version(), entry.publisher(),
            fingerprint, source + "_" + sha256(externalKey).substring(0, 16).toUpperCase(Locale.ROOT),
            component.name(), component.description(), nodeType,
            WorkflowTemplateCatalog.defaultCategory(nodeType), config);
    }

    /** 从插件字段 Schema 提取不含空值的声明默认值。 */
    private ObjectNode defaults(JsonNode schema) {
        ObjectNode defaults = objectMapper.createObjectNode();
        if (schema != null && schema.isArray()) {
            for (JsonNode field : schema) {
                String name = field.path("name").asText("");
                if (!name.isBlank() && field.has("default") && !field.path("default").isNull()) {
                    defaults.set(name, field.path("default").deepCopy());
                }
            }
        }
        return defaults;
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

    /** 构造需要导入时进行 ABI 探测的市场卡片。 */
    private WorkflowModels.MarketplaceNodeView probeView(WorkflowMarketplaceClients.MarketplaceEntry entry,
                                                          String targetNodeType) {
        return new WorkflowModels.MarketplaceNodeView(entry.externalId(), entry.name(), entry.description(),
            entry.version(), entry.publisher(), entry.category(), true, "", targetNodeType,
            WorkflowTemplateCatalog.defaultCategory(targetNodeType), "PROBE_REQUIRED", List.of());
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

    /** 把市场分类或组件类型映射为通用插件节点类型。 */
    private String pluginNodeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (type) {
            case "TRIGGER" -> "PLUGIN_TRIGGER";
            case "MODEL" -> "PLUGIN_MODEL";
            case "DATASOURCE" -> "PLUGIN_DATASOURCE";
            case "AGENT_STRATEGY" -> "PLUGIN_AGENT_STRATEGY";
            case "EXTENSION" -> "PLUGIN_EXTENSION";
            default -> "PLUGIN_ACTION";
        };
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

    /** 计算插件压缩包的稳定 SHA-256 标识。 */
    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
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
    private record PreparedImport(String requestExternalId, WorkflowModels.MarketplaceTemplateDraft draft,
                                  String status) {}
}
