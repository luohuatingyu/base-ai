package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
    private final WorkflowService workflowService;
    private final WorkflowPluginProbeService pluginProbes;
    private final WorkflowPluginRegistryService pluginRegistry;
    private final WorkflowAdapterLifecycleService adapterLifecycleService;
    private final ObjectMapper objectMapper;

    /** 注入市场客户端、异步探测、注册表和模板持久化服务。 */
    public WorkflowNodeMarketplaceService(WorkflowMarketplaceClients clients,
                                          WorkflowService workflowService, WorkflowPluginProbeService pluginProbes,
                                          WorkflowPluginRegistryService pluginRegistry, ObjectMapper objectMapper,
                                          WorkflowAdapterLifecycleService adapterLifecycleService) {
        this.clients = clients;
        this.workflowService = workflowService;
        this.pluginProbes = pluginProbes;
        this.pluginRegistry = pluginRegistry;
        this.objectMapper = objectMapper;
        this.adapterLifecycleService = adapterLifecycleService;
    }

    /** 查询指定来源的全量市场目录，并可只保留当前已兼容节点。 */
    public WorkflowModels.MarketplacePage nodes(String rawSource, String query, String category,
                                                int rawPage, int rawPageSize, boolean compatibleOnly) {
        String source = importSource(rawSource);
        adapterLifecycleService.requireEnabled(source);
        int page = Math.max(1, rawPage);
        int pageSize = Math.min(50, Math.max(1, rawPageSize));
        WorkflowMarketplaceClients.SearchResult result = "N8N".equals(source)
            ? clients.searchN8n(query, page, pageSize) : clients.searchDify(query, category, page, pageSize);
        boolean enqueue = AuthContext.current() != null
            && AuthContext.current().hasPermission("workflow:node:import");
        Map<String, String> activeTemplates = workflowService.activeMarketplaceTemplateFingerprints(source);
        List<WorkflowModels.MarketplaceNodeView> items = new ArrayList<>();
        for (WorkflowMarketplaceClients.MarketplaceEntry entry : result.items()) {
            if ("N8N".equals(source)) items.add(adaptN8n(entry).map(draft -> view(entry, draft, activeTemplates))
                .orElseGet(() -> probeView(source, entry, enqueue, activeTemplates)));
            else if (DIFY_TAVILY.equals(entry.externalId())) {
                items.add(difyPluginView(entry, pluginProbes.snapshot(source, entry, enqueue), activeTemplates));
            } else items.add(probeView(source, entry, enqueue, activeTemplates));
        }
        List<WorkflowModels.MarketplaceNodeView> filtered = compatibleOnly
            ? items.stream().filter(WorkflowModels.MarketplaceNodeView::compatible).toList() : items;
        boolean probePending = items.stream().anyMatch(item -> List.of("NOT_PROBED", "QUEUED", "PROBING")
            .contains(item.probeStatus()));
        return new WorkflowModels.MarketplacePage(source, List.copyOf(filtered), page, pageSize,
            compatibleOnly ? filtered.size() : result.total(), probePending);
    }

    /** 逐项重新确认市场条目和适配器，并返回幂等导入结果。 */
    @Transactional
    public WorkflowModels.MarketplaceImportResult importNodes(String rawSource,
                                                               WorkflowModels.MarketplaceImportCommand command) {
        String source = importSource(rawSource);
        adapterLifecycleService.requireEnabled(source);
        if (command == null || command.externalIds() == null || command.externalIds().isEmpty()) {
            throw new BusinessException("workflow.marketplaceNodeNotFound");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(command.externalIds());
        if (ids.size() > 50 || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 255)) {
            throw new BusinessException("workflow.marketplaceNodeNotFound");
        }
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
                WorkflowModels.MarketplaceTemplateDraft draft = requireDify(externalId);
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

    /** 返回管理员维护的插件准入清单。 */
    public List<WorkflowModels.PluginAdmissionView> pluginAdmissions() { return pluginRegistry.admissions(); }

    /** 保存并返回一份待审批准入资料。 */
    public WorkflowModels.PluginAdmissionView updatePluginAdmission(
        Long pluginId, WorkflowModels.PluginAdmissionCommand command) {
        return pluginRegistry.updateAdmission(pluginId, command);
    }

    /** 批准或拒绝准入资料并同步插件运行状态。 */
    public WorkflowModels.PluginAdmissionView reviewPluginAdmission(
        Long pluginId, WorkflowModels.PluginAdmissionReviewCommand command) {
        return pluginRegistry.reviewAdmission(pluginId, command);
    }

    /** 只消费后台已完成的探测结果，把可执行组件转换为固定版本模板。 */
    private void preparePlugin(String source, String requestExternalId,
                               WorkflowMarketplaceClients.MarketplaceEntry catalogEntry, boolean replaceExisting,
                               List<WorkflowModels.MarketplaceTemplateDraft> drafts, List<PreparedImport> prepared) {
        WorkflowMarketplaceClients.MarketplaceEntry packageEntry = catalogEntry;
        String packageId;
        if ("N8N".equals(source)) {
            packageId = catalogEntry.raw().path("packageName").asText("");
            if (packageId.isBlank()) throw new BusinessException("workflow.marketplacePackageInvalid");
            packageEntry = new WorkflowMarketplaceClients.MarketplaceEntry(packageId, catalogEntry.name(),
                catalogEntry.description(), catalogEntry.version(), catalogEntry.publisher(), catalogEntry.category(),
                catalogEntry.trustLevel(), catalogEntry.raw());
        } else {
            packageId = catalogEntry.externalId();
        }
        WorkflowPluginWorkerClient.WorkerPackage inspected = pluginProbes.requireCompleted(source, catalogEntry);
        String fingerprint = inspected.fingerprint();
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
        String category = marketplaceCategory(entry, component.componentType(), component.externalKey(),
            component.name(), component.description());
        return new WorkflowModels.MarketplaceTemplateDraft(source, externalKey, entry.version(), entry.publisher(),
            fingerprint, source + "_" + sha256(externalKey).substring(0, 16).toUpperCase(Locale.ROOT),
            component.name(), component.description(), nodeType,
            category, config);
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
    private WorkflowModels.MarketplaceTemplateDraft requireDify(String externalId) {
        int separator = externalId.lastIndexOf('/');
        if (separator <= 0) throw new BusinessException("workflow.marketplaceNodeUnsupported");
        String pluginId = externalId.substring(0, separator);
        String toolName = externalId.substring(separator + 1);
        if (!DIFY_TAVILY.equals(pluginId) || !List.of("tavily_search", "tavily_extract").contains(toolName)) {
            throw new BusinessException("workflow.marketplaceNodeUnsupported");
        }
        WorkflowMarketplaceClients.MarketplaceEntry plugin = clients.findDify(pluginId)
            .orElseThrow(() -> new BusinessException("workflow.marketplaceNodeNotFound"));
        WorkflowPluginWorkerClient.WorkerPackage inspected = pluginProbes.requireCompleted("DIFY", plugin);
        WorkflowPluginWorkerClient.WorkerComponent component = inspected.components().stream()
            .filter(item -> toolName.equals(item.externalId()) && "SUPPORTED".equals(item.compatibilityStatus()))
            .findFirst().orElseThrow(() -> new BusinessException("workflow.marketplaceNodeUnsupported"));
        NativeDraft nativeDraft = difyTool(plugin, toolName,
            component.name().isBlank() ? toolName : component.name(), component.description());
        String fingerprint = sha256(inspected.fingerprint() + "\n" + toolName + "\n" + component.schema());
        return new WorkflowModels.MarketplaceTemplateDraft("DIFY", externalId, plugin.version(), plugin.publisher(),
            fingerprint, "DIFY_" + sha256(externalId).substring(0, 16).toUpperCase(Locale.ROOT),
            nativeDraft.name(), nativeDraft.description(), nativeDraft.nodeType(), nativeDraft.functionalCategory(),
            nativeDraft.config().deepCopy());
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
                                                     NativeDraft draft, Map<String, String> activeTemplates) {
        return draft == null
            ? new WorkflowModels.MarketplaceNodeView(entry.externalId(), entry.name(), entry.description(),
                entry.version(), entry.publisher(), entry.category(), false, "NO_NATIVE_ADAPTER", "", "",
                "NONE", List.of(), "NOT_REQUIRED", false)
            : view(draft, entry.version(), entry.publisher(), entry.category(),
                templateImported(activeTemplates, draft("N8N", entry, entry.externalId(), draft)));
    }

    /** 根据后台探测快照构造市场卡片，未完成探测的条目始终不可导入。 */
    private WorkflowModels.MarketplaceNodeView probeView(String source,
                                                          WorkflowMarketplaceClients.MarketplaceEntry entry,
                                                          boolean enqueue,
                                                          Map<String, String> activeTemplates) {
        WorkflowPluginProbeService.ProbeSnapshot snapshot = pluginProbes.snapshot(source, entry, enqueue);
        WorkflowPluginWorkerClient.WorkerComponent supported = snapshot.inspected() == null ? null
            : snapshot.inspected().components().stream()
                .filter(item -> "SUPPORTED".equals(item.compatibilityStatus())).findFirst().orElse(null);
        boolean compatible = "COMPLETE".equals(snapshot.probeStatus()) && supported != null;
        String targetNodeType = compatible ? pluginNodeType(supported.componentType())
            : pluginNodeType(entry.category());
        String category = compatible ? marketplaceCategory(entry, supported.componentType(), supported.externalId(),
            supported.name(), supported.description()) : WorkflowTemplateCatalog.defaultCategory(targetNodeType);
        return new WorkflowModels.MarketplaceNodeView(entry.externalId(), entry.name(), entry.description(),
            entry.version(), entry.publisher(), entry.category(), compatible,
            compatible ? "" : incompatibility(snapshot), targetNodeType,
            category, snapshot.compatibilityStatus(), List.of(), snapshot.probeStatus(),
            compatible && pluginImported(source, entry, snapshot.inspected(), activeTemplates));
    }

    /** 构造兼容市场节点卡片。 */
    private WorkflowModels.MarketplaceNodeView view(NativeDraft draft, String version, String publisher,
                                                     String category, boolean imported) {
        return new WorkflowModels.MarketplaceNodeView(draft.externalId(), draft.name(), draft.description(), version,
            publisher, category, true, "", draft.nodeType(), draft.functionalCategory(), "NATIVE_SUBSET", List.of(),
            "NOT_REQUIRED", imported);
    }

    /** 以插件为分页单位展示 Dify，并把受支持工具作为可选择动作返回。 */
    private WorkflowModels.MarketplaceNodeView difyPluginView(WorkflowMarketplaceClients.MarketplaceEntry plugin,
                                                               WorkflowPluginProbeService.ProbeSnapshot snapshot,
                                                               Map<String, String> activeTemplates) {
        List<WorkflowModels.MarketplaceActionView> actions = List.of(
            action(difyTool(plugin, "tavily_search", "Tavily Search", "Search the web with Tavily"), snapshot,
                activeTemplates),
            action(difyTool(plugin, "tavily_extract", "Tavily Extract", "Extract content from web pages with Tavily"),
                snapshot, activeTemplates)
        );
        boolean compatible = actions.stream().anyMatch(WorkflowModels.MarketplaceActionView::compatible);
        boolean imported = compatible && actions.stream().filter(WorkflowModels.MarketplaceActionView::compatible)
            .allMatch(WorkflowModels.MarketplaceActionView::imported);
        return new WorkflowModels.MarketplaceNodeView(plugin.externalId(), plugin.name(), plugin.description(),
            plugin.version(), plugin.publisher(), plugin.category(), compatible,
            compatible ? "" : incompatibility(snapshot), "TAVILY_TOOL", "NETWORK_API",
            compatible ? "NATIVE_SUBSET" : snapshot.compatibilityStatus(), actions, snapshot.probeStatus(), imported);
    }

    /** 把单个原生适配动作与真实 ABI 组件探测结果合并。 */
    private WorkflowModels.MarketplaceActionView action(NativeDraft draft,
                                                         WorkflowPluginProbeService.ProbeSnapshot snapshot,
                                                         Map<String, String> activeTemplates) {
        String toolName = draft.externalId().substring(draft.externalId().lastIndexOf('/') + 1);
        WorkflowPluginWorkerClient.WorkerComponent component = snapshot.inspected() == null ? null
            : snapshot.inspected().components().stream().filter(item -> toolName.equals(item.externalId())
                && "SUPPORTED".equals(item.compatibilityStatus())).findFirst().orElse(null);
        boolean compatible = "COMPLETE".equals(snapshot.probeStatus()) && component != null;
        String fingerprint = compatible
            ? sha256(snapshot.inspected().fingerprint() + "\n" + toolName + "\n" + component.schema()) : "";
        return new WorkflowModels.MarketplaceActionView(draft.externalId(), draft.name(), draft.description(), compatible,
            compatible ? "" : incompatibility(snapshot), draft.nodeType(), draft.functionalCategory(),
            compatible ? "NATIVE_SUBSET" : snapshot.compatibilityStatus(),
            compatible && fingerprintMatches(activeTemplates, draft.externalId(), fingerprint));
    }

    /** 校验通用插件当前全部受支持组件均有未作废且指纹一致的模板。 */
    private boolean pluginImported(String source, WorkflowMarketplaceClients.MarketplaceEntry entry,
                                   WorkflowPluginWorkerClient.WorkerPackage inspected,
                                   Map<String, String> activeTemplates) {
        if (inspected == null) return false;
        String packageKey = "N8N".equals(source) ? entry.raw().path("packageName").asText("") : entry.externalId();
        if (packageKey.isBlank()) return false;
        List<WorkflowPluginWorkerClient.WorkerComponent> supported = inspected.components().stream()
            .filter(component -> "SUPPORTED".equals(component.compatibilityStatus())).toList();
        return !supported.isEmpty() && supported.stream().allMatch(component -> {
            String schemaFingerprint = sha256(component.componentType() + "\n" + schemaJson(component.schema())
                + "\n" + schemaJson(component.credentialSchema()));
            String templateFingerprint = sha256(inspected.fingerprint() + "\n" + schemaFingerprint);
            return fingerprintMatches(activeTemplates, packageKey + "/" + component.externalId(), templateFingerprint);
        });
    }

    /** 校验单个原生市场模板仍存在且身份指纹没有变化。 */
    private boolean templateImported(Map<String, String> activeTemplates,
                                     WorkflowModels.MarketplaceTemplateDraft draft) {
        return fingerprintMatches(activeTemplates, draft.externalKey(), draft.externalFingerprint());
    }

    /** 使用大小写不敏感的十六进制比较识别当前市场模板。 */
    private boolean fingerprintMatches(Map<String, String> activeTemplates, String externalKey,
                                       String expectedFingerprint) {
        String storedFingerprint = activeTemplates.get(externalKey);
        return storedFingerprint != null && expectedFingerprint != null
            && storedFingerprint.equalsIgnoreCase(expectedFingerprint);
    }

    /** 复用注册表的空 Schema 规范化规则生成稳定组件指纹。 */
    private String schemaJson(JsonNode schema) {
        return schema == null || schema.isMissingNode() ? "[]" : schema.toString();
    }

    /** 把内部探测错误收敛为不会泄漏 Worker 细节的稳定前端原因码。 */
    private String incompatibility(WorkflowPluginProbeService.ProbeSnapshot snapshot) {
        return switch (snapshot.probeStatus()) {
            case "QUEUED", "PROBING", "NOT_PROBED" -> "PROBE_PENDING";
            case "REJECTED" -> publicProbeReason(snapshot.compatibilityReason(), "PACKAGE_REJECTED");
            case "FAILED" -> "PROBE_FAILED";
            case "COMPLETE" -> publicProbeReason(snapshot.compatibilityReason(), "NO_EXECUTABLE_COMPONENT");
            default -> "PROBE_FAILED";
        };
    }

    /** 只向前端返回已审查的稳定探测原因，未知 Worker 细节统一收敛。 */
    private String publicProbeReason(String reason, String fallback) {
        return List.of("PACKAGE_SIZE_LIMIT", "PACKAGE_CONTENT_LIMIT", "PACKAGE_DEPENDENCY_REJECTED",
            "PACKAGE_ARCHIVE_INVALID", "PACKAGE_REJECTED", "ROUTING_UNSUPPORTED", "PLUGIN_ABI_UNSUPPORTED",
            "DEPENDENCY_UNAVAILABLE", "NO_EXECUTABLE_COMPONENT").contains(reason) ? reason : fallback;
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

    /** 使用包身份、市场说明和组件声明推导插件实际功能分类。 */
    private String marketplaceCategory(WorkflowMarketplaceClients.MarketplaceEntry entry, String componentType,
                                       String componentExternalId, String componentName, String componentDescription) {
        return WorkflowTemplateCatalog.marketplaceCategory(componentType, entry.externalId(), entry.name(),
            entry.description(), entry.category(), componentExternalId, componentName, componentDescription);
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

    /** 计算插件压缩包的稳定 SHA-256 标识，供后台探测服务复用。 */
    static String sha256Bytes(byte[] value) {
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
