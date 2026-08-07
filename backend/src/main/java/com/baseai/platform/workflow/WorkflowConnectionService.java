package com.baseai.platform.workflow;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 管理工作流外部连接，并保证敏感配置始终加密保存和脱敏返回。 */
@Service
public class WorkflowConnectionService {
    private static final String MASK = "******";
    private static final Set<String> TYPES = Set.of("MYSQL", "POSTGRESQL", "REDIS", "S3", "KAFKA", "RABBITMQ", "WEBHOOK");
    private static final Set<String> SECRET_FIELDS = Set.of(
        "password", "secret", "secretkey", "accesskey", "token", "apikey", "saslpassword", "privatekey"
    );
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;

    /** 注入 MySQL、JSON 和加密服务。 */
    public WorkflowConnectionService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper, ConfigCryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
    }

    /** 查询当前用户可见的连接，管理员可以查看全部脱敏记录。 */
    public List<WorkflowModels.ConnectionView> connections() {
        AuthUser user = AuthContext.require();
        String sql = "SELECT * FROM workflow_connection WHERE voided=false "
            + (user.roles().contains("ADMIN") ? "" : "AND owner_user_id=? ") + "ORDER BY id DESC";
        return user.roles().contains("ADMIN")
            ? jdbcTemplate.query(sql, (rs, row) -> mapView(rs))
            : jdbcTemplate.query(sql, (rs, row) -> mapView(rs), user.id());
    }

    /** 创建由当前用户拥有的加密连接。 */
    @Transactional
    public WorkflowModels.ConnectionView create(WorkflowModels.ConnectionCommand command) {
        validate(command);
        try {
            jdbcTemplate.update("""
                INSERT INTO workflow_connection(code,name,connection_type,config_encrypted,owner_user_id,enabled)
                VALUES (?,?,?,?,?,?)
                """, code(command.code()), text(command.name()), type(command.connectionType()), encrypt(command.config()),
                AuthContext.require().id(), !Boolean.FALSE.equals(command.enabled()));
            Long id = jdbcTemplate.queryForObject("SELECT id FROM workflow_connection WHERE code=?", Long.class, code(command.code()));
            return view(id);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "workflow.connectionCodeExists");
        }
    }

    /** 更新连接，并在脱敏占位符出现时保留原敏感字段。 */
    @Transactional
    public WorkflowModels.ConnectionView update(Long id, WorkflowModels.ConnectionCommand command) {
        StoredConnection existing = requireOwned(id);
        validate(command);
        ObjectNode merged = mergeMasked(existing.config(), command.config());
        try {
            jdbcTemplate.update("""
                UPDATE workflow_connection SET code=?,name=?,connection_type=?,config_encrypted=?,enabled=?,updated_at=NOW()
                WHERE id=? AND voided=false
                """, code(command.code()), text(command.name()), type(command.connectionType()), encrypt(merged),
                !Boolean.FALSE.equals(command.enabled()), id);
            return view(id);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(409, "workflow.connectionCodeExists");
        }
    }

    /** 软删除未被工作流版本引用的连接。 */
    @Transactional
    public void delete(Long id) {
        requireOwned(id);
        String marker = "\"connectionId\":" + id;
        Integer references = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workflow_version WHERE graph_json LIKE ?", Integer.class, "%" + marker + "%");
        if (references != null && references > 0) throw new BusinessException(409, "workflow.connectionInUse");
        jdbcTemplate.update("UPDATE workflow_connection SET enabled=false,voided=true,updated_at=NOW() WHERE id=?", id);
    }

    /** 返回单条脱敏连接。 */
    public WorkflowModels.ConnectionView view(Long id) { return mapView(requireStored(id)); }

    /** 在保存工作流时确认连接存在、启用且属于工作流所有者。 */
    public void requireOwnedAndEnabled(Long id, Long ownerId, Set<String> allowedTypes) {
        StoredConnection connection = requireStored(id);
        if (!connection.enabled() || !connection.ownerUserId().equals(ownerId) || !allowedTypes.contains(connection.connectionType())) {
            throw new BusinessException("workflow.connectionForbidden");
        }
    }

    /** 为节点执行器读取已授权连接的明文配置。 */
    public StoredConnection resolved(Long id, Set<String> allowedTypes) {
        StoredConnection connection = requireStored(id);
        if (!connection.enabled() || !allowedTypes.contains(connection.connectionType())) {
            throw new BusinessException("workflow.connectionInvalid");
        }
        return connection;
    }

    /** 为当前所有者执行连接测试读取明文配置。 */
    public StoredConnection ownedForTest(Long id) { return requireOwned(id); }

    /** 当前用户只能维护自己的连接，管理员仍需显式成为记录所有者。 */
    private StoredConnection requireOwned(Long id) {
        StoredConnection connection = requireStored(id);
        if (!connection.ownerUserId().equals(AuthContext.require().id())) throw BusinessException.forbidden("workflow.connectionForbidden");
        return connection;
    }

    /** 从数据库读取未作废连接。 */
    private StoredConnection requireStored(Long id) {
        List<StoredConnection> rows = jdbcTemplate.query("SELECT * FROM workflow_connection WHERE id=? AND voided=false",
            (rs, row) -> mapStored(rs), id);
        if (rows.isEmpty()) throw BusinessException.notFound("workflow.connectionNotFound");
        return rows.get(0);
    }

    /** 校验连接命令基础字段。 */
    private void validate(WorkflowModels.ConnectionCommand command) {
        if (command == null || text(command.code()).isBlank() || text(command.name()).isBlank()
            || command.config() == null || !command.config().isObject()) {
            throw new BusinessException("workflow.connectionInvalid");
        }
        type(command.connectionType());
    }

    /** 递归保留更新命令中的脱敏字段原值。 */
    private ObjectNode mergeMasked(JsonNode existing, JsonNode incoming) {
        ObjectNode result = incoming.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = result.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode oldValue = existing.path(entry.getKey());
            if (entry.getValue().isObject() && oldValue.isObject()) result.set(entry.getKey(), mergeMasked(oldValue, entry.getValue()));
            else if (entry.getValue().isTextual() && MASK.equals(entry.getValue().asText())) result.set(entry.getKey(), oldValue.deepCopy());
        }
        return result;
    }

    /** 递归复制配置并屏蔽敏感字段。 */
    private JsonNode masked(JsonNode value) {
        if (!value.isObject()) return value.deepCopy();
        ObjectNode output = objectMapper.createObjectNode();
        value.fields().forEachRemaining(entry -> {
            String normalized = entry.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            if (SECRET_FIELDS.stream().anyMatch(normalized::contains) && !entry.getValue().asText("").isBlank()) output.put(entry.getKey(), MASK);
            else output.set(entry.getKey(), masked(entry.getValue()));
        });
        return output;
    }

    /** 映射脱敏视图。 */
    private WorkflowModels.ConnectionView mapView(ResultSet rs) throws SQLException { return mapView(mapStored(rs)); }

    /** 从内部记录创建脱敏视图。 */
    private WorkflowModels.ConnectionView mapView(StoredConnection connection) {
        return new WorkflowModels.ConnectionView(connection.id(), connection.code(), connection.name(), connection.connectionType(),
            masked(connection.config()), connection.enabled(), connection.ownerUserId(), connection.createdAt(), connection.updatedAt());
    }

    /** 映射并解密内部连接记录。 */
    private StoredConnection mapStored(ResultSet rs) throws SQLException {
        return new StoredConnection(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("connection_type"), decrypt(rs.getString("config_encrypted")), rs.getLong("owner_user_id"),
            rs.getBoolean("enabled"), timestamp(rs, "created_at"), timestamp(rs, "updated_at"));
    }

    /** 规范连接编码。 */
    private String code(String value) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,79}")) throw new BusinessException("workflow.codeInvalid");
        return normalized;
    }

    /** 规范并验证连接类型。 */
    private String type(String value) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        if (!TYPES.contains(normalized)) throw new BusinessException("workflow.connectionTypeInvalid");
        return normalized;
    }

    /** 规范可选文本。 */
    private String text(String value) { return value == null ? "" : value.trim(); }
    /** 加密 JSON 配置。 */
    private String encrypt(JsonNode value) { return cryptoService.encrypt(json(value)); }
    /** 解密 JSON 配置。 */
    private JsonNode decrypt(String value) {
        try { return objectMapper.readTree(cryptoService.decrypt(value)); }
        catch (Exception exception) { throw new BusinessException("workflow.connectionInvalid"); }
    }
    /** 序列化 JSON 配置。 */
    private String json(JsonNode value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException("workflow.connectionInvalid"); }
    }
    /** 映射 JDBC 时间。 */
    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toLocalDateTime();
    }

    public record StoredConnection(Long id, String code, String name, String connectionType, JsonNode config,
                                   Long ownerUserId, boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
