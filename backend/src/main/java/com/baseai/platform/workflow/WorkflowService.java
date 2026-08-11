package com.baseai.platform.workflow;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 管理节点模板、工作流草稿、不可变版本和发布状态。 */
@Service
public class WorkflowService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final WorkflowGraphValidator graphValidator;
    private final WorkflowNodeConfigValidator nodeConfigValidator;
    private final WorkflowConnectionService connectionService;
    private final WorkflowAccessService accessService;

    /** 注入 MySQL、加密、图结构和节点配置校验组件。 */
    public WorkflowService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                           ConfigCryptoService cryptoService, WorkflowGraphValidator graphValidator,
                           WorkflowNodeConfigValidator nodeConfigValidator, WorkflowConnectionService connectionService,
                           WorkflowAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.graphValidator = graphValidator;
        this.nodeConfigValidator = nodeConfigValidator;
        this.connectionService = connectionService;
        this.accessService = accessService;
    }

    /** 查询全部未作废节点模板，并解密授权页面需要的默认配置。 */
    public List<WorkflowModels.NodeTemplateView> templates() {
        var user = AuthContext.require();
        String sql = "SELECT * FROM workflow_node_template WHERE voided=false "
            + (user.roles().contains("ADMIN") ? "" : "AND (system_template=true OR created_by=?) ")
            + "ORDER BY functional_category,template_source,system_template DESC,id";
        return user.roles().contains("ADMIN") ? jdbcTemplate.query(sql, (rs, row) -> mapTemplate(rs))
            : jdbcTemplate.query(sql, (rs, row) -> mapTemplate(rs), user.id());
    }

    /** 查询指定市场来源下未作废模板的外部键和指纹，不暴露加密配置。 */
    public Map<String, String> activeMarketplaceTemplateFingerprints(String rawSource) {
        String source = WorkflowTemplateCatalog.source(rawSource);
        return jdbcTemplate.query("""
            SELECT external_key,external_fingerprint FROM workflow_node_template
            WHERE template_source=? AND external_key IS NOT NULL AND voided=false
            """, resultSet -> {
                Map<String, String> fingerprints = new LinkedHashMap<>();
                while (resultSet.next()) {
                    String externalKey = resultSet.getString("external_key");
                    String fingerprint = resultSet.getString("external_fingerprint");
                    if (externalKey != null && fingerprint != null) fingerprints.put(externalKey, fingerprint);
                }
                return Map.copyOf(fingerprints);
            }, source);
    }

    /** 创建可复用节点模板。 */
    @Transactional
    public WorkflowModels.NodeTemplateView createTemplate(WorkflowModels.NodeTemplateCommand command) {
        validateTemplate(command, false);
        String nodeType = type(command.nodeType());
        String source = WorkflowTemplateCatalog.source(command.source());
        if (!"SYSTEM".equals(source)) throw new BusinessException("workflow.marketplaceImportRequired");
        try {
            Long id = insertKey("""
                INSERT INTO workflow_node_template(code,name,node_type,description,config_encrypted,system_template,template_source,functional_category,enabled,created_by)
                VALUES (?,?,?,?,?,false,?,?,?,?)
                """, code(command.code()), text(command.name()), nodeType, text(command.description()), encryptJson(command.config()),
                source, WorkflowTemplateCatalog.category(command.functionalCategory(), nodeType),
                !Boolean.FALSE.equals(command.enabled()), AuthContext.require().id());
            return template(id);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "workflow.templateCodeExists");
        }
    }

    /** 更新节点模板，系统和市场模板的编码及节点类型保持不可变。 */
    @Transactional
    public WorkflowModels.NodeTemplateView updateTemplate(Long id, WorkflowModels.NodeTemplateCommand command) {
        WorkflowModels.NodeTemplateView existing = templateForManagement(id);
        validateTemplate(command, existing.systemTemplate());
        boolean identityLocked = existing.systemTemplate() || existing.importedTemplate();
        String savedCode = identityLocked ? existing.code() : code(command.code());
        String savedType = identityLocked ? existing.nodeType() : type(command.nodeType());
        try {
            jdbcTemplate.update("""
                UPDATE workflow_node_template SET code=?,name=?,node_type=?,description=?,config_encrypted=?,template_source=?,functional_category=?,enabled=?,updated_at=NOW()
                WHERE id=? AND voided=false
                """, savedCode, text(command.name()), savedType, text(command.description()), encryptJson(command.config()),
                existing.importedTemplate() ? existing.source() : WorkflowTemplateCatalog.updatedSource(command.source(), existing.source()),
                WorkflowTemplateCatalog.updatedCategory(command.functionalCategory(), existing.functionalCategory(), savedType),
                !Boolean.FALSE.equals(command.enabled()), id);
            return template(id);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "workflow.templateCodeExists");
        }
    }

    /** 软删除用户模板，系统内置模板不可删除。 */
    @Transactional
    public void deleteTemplate(Long id) {
        WorkflowModels.NodeTemplateView existing = templateForManagement(id);
        if (existing.systemTemplate()) throw new BusinessException(409, "workflow.systemTemplateProtected");
        jdbcTemplate.update("UPDATE workflow_node_template SET enabled=false,voided=true,updated_at=NOW() WHERE id=?", id);
    }

    /** 兼容单项调用，并委托批量事务实现保证一致的版本更新语义。 */
    @Transactional
    public WorkflowModels.MarketplaceTemplatePersistence importMarketplaceTemplate(WorkflowModels.MarketplaceTemplateDraft draft) {
        return importMarketplaceTemplates(List.of(draft), false).get(0);
    }

    /** 在单一事务中幂等导入市场模板，版本变化必须显式确认后才重置配置。 */
    @Transactional
    public List<WorkflowModels.MarketplaceTemplatePersistence> importMarketplaceTemplates(
        List<WorkflowModels.MarketplaceTemplateDraft> drafts, boolean replaceExisting) {
        if (drafts == null || drafts.isEmpty()) throw new BusinessException("workflow.marketplaceNodeNotFound");
        List<WorkflowModels.MarketplaceTemplatePersistence> results = new ArrayList<>();
        for (WorkflowModels.MarketplaceTemplateDraft draft : drafts) {
            results.add(importMarketplaceTemplateItem(draft, replaceExisting));
        }
        return List.copyOf(results);
    }

    /** 保存单个已验证市场草稿；仅供批量事务入口调用。 */
    private WorkflowModels.MarketplaceTemplatePersistence importMarketplaceTemplateItem(
        WorkflowModels.MarketplaceTemplateDraft draft, boolean replaceExisting) {
        if (draft == null || draft.externalKey() == null || draft.externalKey().isBlank()) {
            throw new BusinessException("workflow.marketplaceNodeNotFound");
        }
        String source = WorkflowTemplateCatalog.source(draft.source());
        if ("SYSTEM".equals(source)) throw new BusinessException("workflow.marketplaceSourceInvalid");
        String nodeType = type(draft.nodeType());
        String category = WorkflowTemplateCatalog.category(draft.functionalCategory(), nodeType);
        if (draft.config() != null && !draft.config().isObject()) throw new BusinessException("workflow.templateConfigInvalid");
        List<MarketplaceExistingTemplate> existing = jdbcTemplate.query("""
            SELECT id,voided,external_fingerprint FROM workflow_node_template
            WHERE template_source=? AND external_key=? ORDER BY id LIMIT 1
            """, (rs, row) -> new MarketplaceExistingTemplate(rs.getLong("id"), rs.getBoolean("voided"),
                rs.getString("external_fingerprint")), source, marketplaceText(draft.externalKey(), 255));
        if (!existing.isEmpty()) {
            MarketplaceExistingTemplate current = existing.get(0);
            Long id = current.id();
            boolean changed = current.fingerprint() == null
                || !current.fingerprint().equalsIgnoreCase(fingerprint(draft.externalFingerprint()));
            if (!current.voided() && !changed) {
                jdbcTemplate.update("UPDATE workflow_node_template SET enabled=true,updated_at=NOW() WHERE id=?", id);
                return new WorkflowModels.MarketplaceTemplatePersistence(id, "ALREADY_IMPORTED");
            }
            if (!current.voided() && !replaceExisting) {
                return new WorkflowModels.MarketplaceTemplatePersistence(id, "UPDATE_AVAILABLE");
            }
            jdbcTemplate.update("""
                UPDATE workflow_node_template SET code=?,name=?,node_type=?,description=?,config_encrypted=?,system_template=false,
                    functional_category=?,external_version=?,external_publisher=?,external_fingerprint=?,imported_at=NOW(),
                    enabled=true,voided=false,created_by=?,updated_at=NOW() WHERE id=?
                """, code(draft.code()), marketplaceText(draft.name(), 120), nodeType,
                marketplaceText(draft.description(), 500), encryptJson(draft.config()), category,
                marketplaceText(draft.externalVersion(), 64), marketplaceText(draft.externalPublisher(), 120),
                fingerprint(draft.externalFingerprint()), AuthContext.require().id(), id);
            return new WorkflowModels.MarketplaceTemplatePersistence(id, current.voided() ? "RESTORED" : "UPDATED");
        }
        try {
            Long id = insertKey("""
                INSERT INTO workflow_node_template(code,name,node_type,description,config_encrypted,system_template,
                    template_source,functional_category,external_key,external_version,external_publisher,
                    external_fingerprint,imported_at,enabled,created_by)
                VALUES (?,?,?,?,?,false,?,?,?,?,?,?,NOW(),true,?)
                """, code(draft.code()), marketplaceText(draft.name(), 120), nodeType,
                marketplaceText(draft.description(), 500), encryptJson(draft.config()), source, category,
                marketplaceText(draft.externalKey(), 255), marketplaceText(draft.externalVersion(), 64),
                marketplaceText(draft.externalPublisher(), 120), fingerprint(draft.externalFingerprint()),
                AuthContext.require().id());
            return new WorkflowModels.MarketplaceTemplatePersistence(id, "CREATED");
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "workflow.templateCodeExists");
        }
    }

    /** 按 ID 查询单个节点模板。 */
    public WorkflowModels.NodeTemplateView template(Long id) {
        WorkflowModels.NodeTemplateView template = storedTemplate(id);
        Long createdBy = jdbcTemplate.queryForObject("SELECT created_by FROM workflow_node_template WHERE id=?", Long.class, id);
        if (!template.systemTemplate()) accessService.requireTemplateOwnerOrAdmin(createdBy, false);
        return template;
    }

    /** 查询模板并执行维护权限校验。 */
    private WorkflowModels.NodeTemplateView templateForManagement(Long id) {
        WorkflowModels.NodeTemplateView template = storedTemplate(id);
        Long createdBy = jdbcTemplate.queryForObject("SELECT created_by FROM workflow_node_template WHERE id=?", Long.class, id);
        accessService.requireTemplateOwnerOrAdmin(createdBy, template.systemTemplate());
        return template;
    }

    /** 内部读取单个未作废模板。 */
    private WorkflowModels.NodeTemplateView storedTemplate(Long id) {
        List<WorkflowModels.NodeTemplateView> rows = jdbcTemplate.query(
            "SELECT * FROM workflow_node_template WHERE id=? AND voided=false", (rs, row) -> mapTemplate(rs), id);
        if (rows.isEmpty()) throw BusinessException.notFound("workflow.templateNotFound");
        return rows.get(0);
    }

    /** 查询工作流列表并携带当前草稿画布。 */
    public List<WorkflowModels.WorkflowView> workflows() {
        var user = AuthContext.require();
        String sql = workflowSelect() + " WHERE d.voided=false "
            + (user.roles().contains("ADMIN") ? "" : "AND d.owner_user_id=? ") + "ORDER BY d.id DESC";
        return user.roles().contains("ADMIN") ? jdbcTemplate.query(sql, (rs, row) -> mapWorkflow(rs))
            : jdbcTemplate.query(sql, (rs, row) -> mapWorkflow(rs), user.id());
    }

    /** 创建工作流及首个不可变草稿版本。 */
    @Transactional
    public WorkflowModels.WorkflowView createWorkflow(WorkflowModels.WorkflowCommand command) {
        validateWorkflowCommand(command, false);
        Long ownerId = AuthContext.require().id();
        try {
            Long workflowId = insertKey("""
                INSERT INTO workflow_definition(code,name,description,owner_user_id) VALUES (?,?,?,?)
                """, code(command.code()), text(command.name()), text(command.description()), ownerId);
            Long versionId = insertVersion(workflowId, 1, command.graph(), command.inputSchema(), ownerId, ownerId);
            jdbcTemplate.update("UPDATE workflow_definition SET current_version_id=?,revision=1 WHERE id=?", versionId, workflowId);
            return workflow(workflowId);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "workflow.codeExists");
        }
    }

    /** 保存新草稿版本并通过 revision 防止覆盖其他编辑者的变更。 */
    @Transactional
    public WorkflowModels.WorkflowView updateWorkflow(Long id, WorkflowModels.WorkflowCommand command) {
        WorkflowModels.WorkflowView existing = workflow(id);
        validateWorkflowCommand(command, true);
        if (command.revision() == null || command.revision() != existing.revision()) {
            throw new BusinessException(409, "workflow.revisionConflict");
        }
        int nextVersion = existing.currentVersion() + 1;
        Long versionId = insertVersion(id, nextVersion, command.graph(), command.inputSchema(),
            AuthContext.require().id(), existing.ownerUserId());
        int changed = jdbcTemplate.update("""
            UPDATE workflow_definition SET code=?,name=?,description=?,current_version_id=?,status='DRAFT',revision=revision+1,
                updated_at=NOW() WHERE id=? AND revision=? AND voided=false
            """, code(command.code()), text(command.name()), text(command.description()), versionId, id, command.revision());
        if (changed == 0) throw new BusinessException(409, "workflow.revisionConflict");
        return workflow(id);
    }

    /** 将当前草稿原子标记为对外执行版本。 */
    @Transactional
    public WorkflowModels.WorkflowView publish(Long id) {
        WorkflowModels.WorkflowView existing = workflow(id);
        WorkflowModels.StoredVersion currentVersion = storedVersion(existing.currentVersionId());
        graphValidator.validate(currentVersion.graph());
        validateExecutableConfiguration(currentVersion);
        jdbcTemplate.update("""
            UPDATE workflow_definition SET published_version_id=current_version_id,status='PUBLISHED',enabled=true,
                revision=revision+1,updated_at=NOW() WHERE id=? AND voided=false
            """, id);
        return workflow(id);
    }

    /** 校验指定版本的模板快照和实例配置已满足所有节点运行条件。 */
    public void validateExecutableConfiguration(WorkflowModels.StoredVersion version) {
        nodeConfigValidator.validateForPublish(version.graph(), version.templateSnapshots());
        validateConnectionSnapshot(version);
    }

    /** 软删除定义但保留历史版本和运行记录。 */
    @Transactional
    public void deleteWorkflow(Long id) {
        workflow(id);
        if (jdbcTemplate.update("UPDATE workflow_definition SET enabled=false,voided=true,updated_at=NOW() WHERE id=? AND voided=false", id) == 0) {
            throw BusinessException.notFound("workflow.notFound");
        }
    }

    /** 查询单个工作流及当前草稿。 */
    public WorkflowModels.WorkflowView workflow(Long id) {
        WorkflowModels.WorkflowView workflow = storedWorkflow(id);
        accessService.requireOwnerOrAdmin(workflow.ownerUserId());
        return workflow;
    }

    /** 内部读取单个未作废工作流，不建立调用者权限语义。 */
    private WorkflowModels.WorkflowView storedWorkflow(Long id) {
        List<WorkflowModels.WorkflowView> rows = jdbcTemplate.query(workflowSelect() + " WHERE d.id=? AND d.voided=false",
            (rs, row) -> mapWorkflow(rs), id);
        if (rows.isEmpty()) throw BusinessException.notFound("workflow.notFound");
        return rows.get(0);
    }

    /** 查询单个工作流版本历史。 */
    public List<WorkflowModels.VersionView> versions(Long workflowId) {
        workflow(workflowId);
        return jdbcTemplate.query("SELECT * FROM workflow_version WHERE workflow_id=? ORDER BY version_number DESC", (rs, row) ->
            new WorkflowModels.VersionView(rs.getLong("id"), rs.getInt("version_number"), parseJson(rs.getString("graph_json")),
                parseJson(rs.getString("input_schema_json")), timestamp(rs, "created_at")), workflowId);
    }

    /** 为页面调试解析当前草稿，为开放 API 解析已发布版本。 */
    public WorkflowModels.StoredVersion executable(String code, boolean publishedOnly) {
        WorkflowModels.StoredVersion version = executableInternal(code, publishedOnly);
        accessService.requireExecute(version.workflowId(), version.workflowOwnerId());
        return version;
    }

    /** 为平台原生触发器读取可执行版本，调用方必须已经验证触发定义。 */
    public WorkflowModels.StoredVersion executableInternal(String code, boolean publishedOnly) {
        String column = publishedOnly ? "published_version_id" : "current_version_id";
        List<WorkflowModels.StoredVersion> rows = jdbcTemplate.query("""
            SELECT v.*,d.code workflow_code,d.enabled,d.status,d.owner_user_id workflow_owner_id FROM workflow_definition d
            JOIN workflow_version v ON v.id=d.%s
            WHERE d.code=? AND d.voided=false AND d.enabled=true %s
            """.formatted(column, publishedOnly ? "AND d.status='PUBLISHED'" : ""),
            (rs, row) -> mapStoredVersion(rs), code(code));
        if (rows.isEmpty()) throw BusinessException.notFound("workflow.notFound");
        return rows.get(0);
    }

    /** 按版本 ID 读取执行时的不可变快照。 */
    public WorkflowModels.StoredVersion storedVersion(Long versionId) {
        List<WorkflowModels.StoredVersion> rows = jdbcTemplate.query("""
            SELECT v.*,d.code workflow_code,d.owner_user_id workflow_owner_id FROM workflow_version v JOIN workflow_definition d ON d.id=v.workflow_id WHERE v.id=?
            """, (rs, row) -> mapStoredVersion(rs), versionId);
        if (rows.isEmpty()) throw BusinessException.notFound("workflow.versionNotFound");
        return rows.get(0);
    }

    /** 查询全部已发布工作流中的原生触发节点。 */
    public List<WorkflowModels.TriggerDefinition> triggerDefinitions() {
        List<WorkflowModels.TriggerDefinition> result = new ArrayList<>();
        jdbcTemplate.query("""
            SELECT d.id workflow_id,d.code workflow_code,d.owner_user_id,v.id version_id,v.graph_json,v.template_snapshot_json
            FROM workflow_definition d JOIN workflow_version v ON v.id=d.published_version_id
            WHERE d.voided=false AND d.enabled=true AND d.status='PUBLISHED'
            """, rs -> {
                JsonNode graph = parseJson(rs.getString("graph_json"));
                JsonNode snapshots = parseJson(rs.getString("template_snapshot_json"));
                graph.path("nodes").forEach(node -> {
                    String type = WorkflowGraphValidator.nodeType(node);
                    if (WorkflowNodeTypes.TRIGGERS.contains(type)) {
                        JsonNode config = effectiveConfig(node, snapshots.path(node.path("id").asText()));
                        result.add(new WorkflowModels.TriggerDefinition(rsLong(rs, "workflow_id"), rsString(rs, "workflow_code"),
                            rsLong(rs, "version_id"), rsLong(rs, "owner_user_id"), node.path("id").asText(), type,
                            config.deepCopy()));
                    }
                });
            });
        return result;
    }

    /** 在 ResultSetExtractor 中读取长整型并统一包装 SQL 异常。 */
    private Long rsLong(ResultSet rs, String column) {
        try { return rs.getLong(column); } catch (SQLException exception) { throw new IllegalStateException(exception); }
    }

    /** 在 ResultSetExtractor 中读取字符串并统一包装 SQL 异常。 */
    private String rsString(ResultSet rs, String column) {
        try { return rs.getString(column); } catch (SQLException exception) { throw new IllegalStateException(exception); }
    }

    /** 写入版本时同步固化画布引用模板，避免模板更新污染历史执行。 */
    private Long insertVersion(Long workflowId, int version, JsonNode graph, JsonNode inputSchema,
                               Long createdBy, Long connectionOwnerId) {
        graphValidator.validate(graph);
        ObjectNode snapshots = objectMapper.createObjectNode();
        graph.path("nodes").forEach(node -> {
            JsonNode templateId = node.get("templateId");
            if (templateId != null && templateId.canConvertToLong()) {
                WorkflowModels.NodeTemplateView template = template(templateId.asLong());
                ObjectNode snapshot = snapshots.putObject(node.path("id").asText());
                snapshot.put("templateId", template.id()).put("code", template.code()).put("nodeType", template.nodeType())
                    .put("source", template.source()).put("functionalCategory", template.functionalCategory());
                snapshot.set("config", template.config());
            }
        });
        Map<Long, Long> connectionRevisions = new LinkedHashMap<>();
        validateConnections(graph, snapshots, connectionOwnerId, connectionRevisions);
        Long versionId = insertKey("""
            INSERT INTO workflow_version(workflow_id,version_number,graph_json,input_schema_json,template_snapshot_json,created_by)
            VALUES (?,?,?,?,?,?)
            """, workflowId, version, json(graph), json(inputSchema), json(snapshots), createdBy);
        connectionRevisions.forEach((connectionId, revision) -> jdbcTemplate.update("""
            INSERT INTO workflow_version_connection(workflow_version_id,connection_id,security_revision) VALUES (?,?,?)
            """, versionId, connectionId, revision));
        jdbcTemplate.update("UPDATE workflow_version SET connection_snapshot_complete=true WHERE id=?", versionId);
        return versionId;
    }

    /** 校验主图和嵌套子图引用的连接均属于工作流所有者且类型匹配。 */
    private void validateConnections(JsonNode graph, JsonNode snapshots, Long ownerId, Map<Long, Long> revisions) {
        graph.path("nodes").forEach(node -> {
            String nodeType = WorkflowGraphValidator.nodeType(node);
            JsonNode config = effectiveConfig(node, snapshots.path(node.path("id").asText()));
            if (config.hasNonNull("connectionId")) {
                Set<String> allowed = switch (nodeType) {
                    case "SQL_QUERY" -> Set.of("MYSQL", "POSTGRESQL");
                    case "REDIS_COMMAND" -> Set.of("REDIS");
                    case "S3_OBJECT" -> Set.of("S3");
                    case "KAFKA_PUBLISH", "KAFKA_TRIGGER" -> Set.of("KAFKA");
                    case "RABBITMQ_PUBLISH", "RABBITMQ_TRIGGER" -> Set.of("RABBITMQ");
                    case "IM_NOTIFY", "WEBHOOK_TRIGGER" -> Set.of("WEBHOOK");
                    case "TAVILY_TOOL" -> Set.of("TAVILY");
                    case "PLUGIN_ACTION", "PLUGIN_TRIGGER", "PLUGIN_MODEL", "PLUGIN_DATASOURCE",
                         "PLUGIN_AGENT_STRATEGY", "PLUGIN_EXTENSION" -> Set.of("PLUGIN");
                    default -> Set.of();
                };
                if (allowed.isEmpty()) throw new BusinessException("workflow.connectionForbidden");
                WorkflowConnectionService.StoredConnection connection = connectionService.requireOwnedAndEnabled(
                    config.path("connectionId").asLong(), ownerId, allowed);
                if ("WEBHOOK_TRIGGER".equals(nodeType)
                    && connection.config().path("secret").asText("").getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                    throw new BusinessException("workflow.webhookSecretWeak");
                }
                revisions.put(connection.id(), connection.securityRevision());
            }
            if (WorkflowNodeTypes.NESTED_GRAPH.contains(nodeType) && config.path("bodyGraph").isObject()) {
                validateConnections(config.path("bodyGraph"), objectMapper.createObjectNode(), ownerId, revisions);
            }
        });
    }

    /** 校验不可变版本引用的连接仍保持发布时安全修订，阻止目标被静默改向。 */
    public void validateConnectionSnapshot(WorkflowModels.StoredVersion version) {
        Boolean complete = jdbcTemplate.queryForObject(
            "SELECT connection_snapshot_complete FROM workflow_version WHERE id=?", Boolean.class, version.id());
        if (!Boolean.TRUE.equals(complete)) throw new BusinessException("workflow.connectionSnapshotIncomplete");
        Integer mismatches = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM workflow_version_connection vc
            LEFT JOIN workflow_connection c ON c.id=vc.connection_id AND c.voided=false AND c.enabled=true
            WHERE vc.workflow_version_id=? AND (c.id IS NULL OR c.security_revision<>vc.security_revision)
            """, Integer.class, version.id());
        if (mismatches != null && mismatches > 0) throw new BusinessException("workflow.connectionChanged");
    }

    /** 为升级前历史版本按当前连接修订幂等补齐安全快照。 */
    public void initializeLegacyConnectionSnapshots() {
        jdbcTemplate.query("""
            SELECT v.id,v.graph_json,v.template_snapshot_json,d.owner_user_id FROM workflow_version v
            JOIN workflow_definition d ON d.id=v.workflow_id WHERE v.connection_snapshot_complete=false
            ORDER BY v.id
            """, rs -> {
            Long versionId = rs.getLong("id");
            Map<Long, Long> revisions = new LinkedHashMap<>();
            try {
                validateConnections(parseJson(rs.getString("graph_json")), parseJson(rs.getString("template_snapshot_json")),
                    rs.getLong("owner_user_id"), revisions);
                revisions.forEach((connectionId, revision) -> jdbcTemplate.update("""
                    INSERT INTO workflow_version_connection(workflow_version_id,connection_id,security_revision)
                    VALUES (?,?,?) ON DUPLICATE KEY UPDATE security_revision=VALUES(security_revision)
                    """, versionId, connectionId, revision));
                jdbcTemplate.update("UPDATE workflow_version SET connection_snapshot_complete=true WHERE id=?", versionId);
            } catch (RuntimeException exception) {
                log.warn("Workflow version connection snapshot remains incomplete: versionId={}", versionId);
            }
        });
    }

    /** 合并不可变模板快照和画布实例覆盖，供触发器扫描及权限校验复用。 */
    private ObjectNode effectiveConfig(JsonNode node, JsonNode snapshot) {
        ObjectNode result = objectMapper.createObjectNode();
        if (snapshot.path("config").isObject()) result.setAll((ObjectNode) snapshot.path("config").deepCopy());
        JsonNode own = node.path("config").isObject() ? node.path("config") : node.path("data").path("config");
        if (own.isObject()) deepMerge(result, own);
        return result;
    }

    /** 递归应用实例配置，避免局部覆盖时丢失模板中的嵌套字段。 */
    private void deepMerge(ObjectNode target, JsonNode source) {
        source.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject() && target.path(entry.getKey()).isObject()) {
                deepMerge((ObjectNode) target.path(entry.getKey()), entry.getValue());
            } else target.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }

    /** 校验画布命令基础字段和图结构。 */
    private void validateWorkflowCommand(WorkflowModels.WorkflowCommand command, boolean updating) {
        if (command == null || text(command.name()).isBlank() || text(command.code()).isBlank()) {
            throw new BusinessException("workflow.nameCodeRequired");
        }
        graphValidator.validate(command.graph());
        if (command.inputSchema() != null && !command.inputSchema().isObject()) throw new BusinessException("workflow.inputSchemaInvalid");
        graphValidator.validatePayload(command.inputSchema());
        if (updating && command.revision() == null) throw new BusinessException("workflow.revisionConflict");
    }

    /** 校验模板类型、编码、名称和配置对象。 */
    private void validateTemplate(WorkflowModels.NodeTemplateCommand command, boolean system) {
        if (command == null || text(command.name()).isBlank() || (!system && text(command.code()).isBlank())) {
            throw new BusinessException("workflow.nameCodeRequired");
        }
        type(command.nodeType());
        WorkflowTemplateCatalog.source(command.source());
        WorkflowTemplateCatalog.category(command.functionalCategory(), command.nodeType());
        if (command.config() != null && !command.config().isObject()) throw new BusinessException("workflow.templateConfigInvalid");
    }

    /** 构造定义和版本联查 SQL。 */
    private String workflowSelect() {
        return """
            SELECT d.*,cv.version_number current_version,cv.graph_json,cv.input_schema_json,
                pv.version_number published_version FROM workflow_definition d
            JOIN workflow_version cv ON cv.id=d.current_version_id
            LEFT JOIN workflow_version pv ON pv.id=d.published_version_id
            """;
    }

    /** 映射节点模板并解密默认配置。 */
    private WorkflowModels.NodeTemplateView mapTemplate(ResultSet rs) throws SQLException {
        String externalKey = rs.getString("external_key");
        return new WorkflowModels.NodeTemplateView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("node_type"), rs.getString("description"), decryptJson(rs.getString("config_encrypted")),
            rs.getBoolean("system_template"), rs.getString("template_source"), rs.getString("functional_category"),
            rs.getBoolean("enabled"), externalKey != null && !externalKey.isBlank(), externalKey,
            rs.getString("external_version"), rs.getString("external_publisher"), rs.getString("external_fingerprint"),
            timestamp(rs, "imported_at"), timestamp(rs, "created_at"), timestamp(rs, "updated_at"));
    }

    /** 截断不可信市场文案以满足数据库列约束。 */
    private String marketplaceText(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    /** 只接受适配器生成的 SHA-256 指纹。 */
    private String fingerprint(String value) {
        String normalized = marketplaceText(value, 64).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new BusinessException("workflow.marketplaceResponseInvalid");
        return normalized;
    }

    /** 映射工作流定义和当前版本。 */
    private WorkflowModels.WorkflowView mapWorkflow(ResultSet rs) throws SQLException {
        return new WorkflowModels.WorkflowView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("description"), rs.getString("status"), nullableLong(rs, "current_version_id"),
            rs.getInt("current_version"), nullableLong(rs, "published_version_id"), nullableInteger(rs, "published_version"),
            rs.getLong("revision"), rs.getBoolean("enabled"), rs.getLong("owner_user_id"), parseJson(rs.getString("graph_json")),
            parseJson(rs.getString("input_schema_json")), timestamp(rs, "created_at"), timestamp(rs, "updated_at"));
    }

    /** 映射执行器使用的不可变版本。 */
    private WorkflowModels.StoredVersion mapStoredVersion(ResultSet rs) throws SQLException {
        return new WorkflowModels.StoredVersion(rs.getLong("id"), rs.getLong("workflow_id"), rs.getString("workflow_code"),
            rs.getInt("version_number"), parseJson(rs.getString("graph_json")), parseJson(rs.getString("input_schema_json")),
            parseJson(rs.getString("template_snapshot_json")), rs.getLong("workflow_owner_id"));
    }

    /** 加密 JSON 配置。 */
    private String encryptJson(JsonNode value) { return cryptoService.encrypt(json(value)); }
    /** 解密 JSON 配置。 */
    private JsonNode decryptJson(String value) { return value == null || value.isBlank() ? objectMapper.createObjectNode() : parseJson(cryptoService.decrypt(value)); }
    /** 序列化 JSON，空值统一保存为空对象。 */
    private String json(JsonNode value) {
        try { return objectMapper.writeValueAsString(value == null || value.isNull() ? objectMapper.createObjectNode() : value); }
        catch (Exception exception) { throw new BusinessException("workflow.jsonInvalid"); }
    }
    /** 解析持久化 JSON。 */
    private JsonNode parseJson(String value) {
        try { return objectMapper.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (Exception exception) { throw new BusinessException("workflow.jsonInvalid"); }
    }
    /** 规范并校验节点类型。 */
    private String type(String value) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        if (!WorkflowNodeTypes.ALL.contains(normalized)) throw new BusinessException("workflow.nodeTypeInvalid");
        return normalized;
    }
    /** 规范并校验模板或工作流编码。 */
    private String code(String value) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,79}")) throw new BusinessException("workflow.codeInvalid");
        return normalized;
    }
    /** 规范可选文本。 */
    private String text(String value) { return value == null ? "" : value.trim(); }
    /** 读取可空长整型列。 */
    private Long nullableLong(ResultSet rs, String column) throws SQLException { long value = rs.getLong(column); return rs.wasNull() ? null : value; }
    /** 读取可空整型列。 */
    private Integer nullableInteger(ResultSet rs, String column) throws SQLException { int value = rs.getInt(column); return rs.wasNull() ? null : value; }
    /** 将 JDBC 时间戳安全映射为本地时间。 */
    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toLocalDateTime();
    }

    /** 使用 MySQL 自增主键创建记录并返回生成 ID。 */
    private Long insertKey(String sql, Object... arguments) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("MySQL 未返回工作流自增主键");
        return key.longValue();
    }

    /** 保存市场模板当前身份状态，避免读取与更新之间重复查询。 */
    private record MarketplaceExistingTemplate(Long id, boolean voided, String fingerprint) { }
}
