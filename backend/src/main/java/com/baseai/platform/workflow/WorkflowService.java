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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 管理节点模板、工作流草稿、不可变版本和发布状态。 */
@Service
public class WorkflowService {
    private static final Set<String> TYPES = Set.of("START", "END", "LLM", "HTTP", "AGENT", "CONDITION", "ITERATION", "LOOP");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final WorkflowGraphValidator graphValidator;

    /** 注入 MySQL、加密和图校验组件。 */
    public WorkflowService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                           ConfigCryptoService cryptoService, WorkflowGraphValidator graphValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.graphValidator = graphValidator;
    }

    /** 查询全部未作废节点模板，并解密授权页面需要的默认配置。 */
    public List<WorkflowModels.NodeTemplateView> templates() {
        return jdbcTemplate.query("SELECT * FROM workflow_node_template WHERE voided=false ORDER BY system_template DESC, id",
            (rs, row) -> mapTemplate(rs));
    }

    /** 创建可复用节点模板。 */
    @Transactional
    public WorkflowModels.NodeTemplateView createTemplate(WorkflowModels.NodeTemplateCommand command) {
        validateTemplate(command, false);
        try {
            Long id = insertKey("""
                INSERT INTO workflow_node_template(code,name,node_type,description,config_encrypted,system_template,enabled,created_by)
                VALUES (?,?,?,?,?,false,?,?)
                """, code(command.code()), text(command.name()), type(command.nodeType()), text(command.description()),
                encryptJson(command.config()), !Boolean.FALSE.equals(command.enabled()), AuthContext.require().id());
            return template(id);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "workflow.templateCodeExists");
        }
    }

    /** 更新用户节点模板，系统模板只允许调整默认配置和启用状态。 */
    @Transactional
    public WorkflowModels.NodeTemplateView updateTemplate(Long id, WorkflowModels.NodeTemplateCommand command) {
        WorkflowModels.NodeTemplateView existing = template(id);
        validateTemplate(command, existing.systemTemplate());
        String savedCode = existing.systemTemplate() ? existing.code() : code(command.code());
        String savedType = existing.systemTemplate() ? existing.nodeType() : type(command.nodeType());
        try {
            jdbcTemplate.update("""
                UPDATE workflow_node_template SET code=?,name=?,node_type=?,description=?,config_encrypted=?,enabled=?,updated_at=NOW()
                WHERE id=? AND voided=false
                """, savedCode, text(command.name()), savedType, text(command.description()), encryptJson(command.config()),
                !Boolean.FALSE.equals(command.enabled()), id);
            return template(id);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "workflow.templateCodeExists");
        }
    }

    /** 软删除用户模板，系统内置模板不可删除。 */
    @Transactional
    public void deleteTemplate(Long id) {
        WorkflowModels.NodeTemplateView existing = template(id);
        if (existing.systemTemplate()) throw new BusinessException(409, "workflow.systemTemplateProtected");
        jdbcTemplate.update("UPDATE workflow_node_template SET enabled=false,voided=true,updated_at=NOW() WHERE id=?", id);
    }

    /** 按 ID 查询单个节点模板。 */
    public WorkflowModels.NodeTemplateView template(Long id) {
        List<WorkflowModels.NodeTemplateView> rows = jdbcTemplate.query(
            "SELECT * FROM workflow_node_template WHERE id=? AND voided=false", (rs, row) -> mapTemplate(rs), id);
        if (rows.isEmpty()) throw BusinessException.notFound("workflow.templateNotFound");
        return rows.get(0);
    }

    /** 查询工作流列表并携带当前草稿画布。 */
    public List<WorkflowModels.WorkflowView> workflows() {
        return jdbcTemplate.query(workflowSelect() + " WHERE d.voided=false ORDER BY d.id DESC", (rs, row) -> mapWorkflow(rs));
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
            Long versionId = insertVersion(workflowId, 1, command.graph(), command.inputSchema(), ownerId);
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
        Long versionId = insertVersion(id, nextVersion, command.graph(), command.inputSchema(), AuthContext.require().id());
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
        graphValidator.validate(existing.graph());
        jdbcTemplate.update("""
            UPDATE workflow_definition SET published_version_id=current_version_id,status='PUBLISHED',enabled=true,
                revision=revision+1,updated_at=NOW() WHERE id=? AND voided=false
            """, id);
        return workflow(id);
    }

    /** 软删除定义但保留历史版本和运行记录。 */
    @Transactional
    public void deleteWorkflow(Long id) {
        if (jdbcTemplate.update("UPDATE workflow_definition SET enabled=false,voided=true,updated_at=NOW() WHERE id=? AND voided=false", id) == 0) {
            throw BusinessException.notFound("workflow.notFound");
        }
    }

    /** 查询单个工作流及当前草稿。 */
    public WorkflowModels.WorkflowView workflow(Long id) {
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
        String column = publishedOnly ? "published_version_id" : "current_version_id";
        List<WorkflowModels.StoredVersion> rows = jdbcTemplate.query("""
            SELECT v.*,d.code workflow_code,d.enabled,d.status FROM workflow_definition d
            JOIN workflow_version v ON v.id=d.%s
            WHERE d.code=? AND d.voided=false AND d.enabled=true
            """.formatted(column), (rs, row) -> mapStoredVersion(rs), code(code));
        if (rows.isEmpty()) throw BusinessException.notFound("workflow.notFound");
        return rows.get(0);
    }

    /** 按版本 ID 读取执行时的不可变快照。 */
    public WorkflowModels.StoredVersion storedVersion(Long versionId) {
        List<WorkflowModels.StoredVersion> rows = jdbcTemplate.query("""
            SELECT v.*,d.code workflow_code FROM workflow_version v JOIN workflow_definition d ON d.id=v.workflow_id WHERE v.id=?
            """, (rs, row) -> mapStoredVersion(rs), versionId);
        if (rows.isEmpty()) throw BusinessException.notFound("workflow.versionNotFound");
        return rows.get(0);
    }

    /** 写入版本时同步固化画布引用模板，避免模板更新污染历史执行。 */
    private Long insertVersion(Long workflowId, int version, JsonNode graph, JsonNode inputSchema, Long ownerId) {
        graphValidator.validate(graph);
        ObjectNode snapshots = objectMapper.createObjectNode();
        graph.path("nodes").forEach(node -> {
            JsonNode templateId = node.get("templateId");
            if (templateId != null && templateId.canConvertToLong()) {
                WorkflowModels.NodeTemplateView template = template(templateId.asLong());
                ObjectNode snapshot = snapshots.putObject(node.path("id").asText());
                snapshot.put("templateId", template.id()).put("code", template.code()).put("nodeType", template.nodeType());
                snapshot.set("config", template.config());
            }
        });
        return insertKey("""
            INSERT INTO workflow_version(workflow_id,version_number,graph_json,input_schema_json,template_snapshot_json,created_by)
            VALUES (?,?,?,?,?,?)
            """, workflowId, version, json(graph), json(inputSchema), json(snapshots), ownerId);
    }

    /** 校验画布命令基础字段和图结构。 */
    private void validateWorkflowCommand(WorkflowModels.WorkflowCommand command, boolean updating) {
        if (command == null || text(command.name()).isBlank() || text(command.code()).isBlank()) {
            throw new BusinessException("workflow.nameCodeRequired");
        }
        graphValidator.validate(command.graph());
        if (command.inputSchema() != null && !command.inputSchema().isObject()) throw new BusinessException("workflow.inputSchemaInvalid");
        if (updating && command.revision() == null) throw new BusinessException("workflow.revisionConflict");
    }

    /** 校验模板类型、编码、名称和配置对象。 */
    private void validateTemplate(WorkflowModels.NodeTemplateCommand command, boolean system) {
        if (command == null || text(command.name()).isBlank() || (!system && text(command.code()).isBlank())) {
            throw new BusinessException("workflow.nameCodeRequired");
        }
        type(command.nodeType());
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
        return new WorkflowModels.NodeTemplateView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("node_type"), rs.getString("description"), decryptJson(rs.getString("config_encrypted")),
            rs.getBoolean("system_template"), rs.getBoolean("enabled"), timestamp(rs, "created_at"), timestamp(rs, "updated_at"));
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
            parseJson(rs.getString("template_snapshot_json")));
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
        if (!TYPES.contains(normalized)) throw new BusinessException("workflow.nodeTypeInvalid");
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
}
