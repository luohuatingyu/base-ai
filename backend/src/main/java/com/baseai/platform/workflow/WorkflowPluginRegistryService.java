package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** 持久化固定版本插件与组件 Schema，并提供运行时不可伪造身份。 */
@Service
public class WorkflowPluginRegistryService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 注入 MySQL 和 JSON 解析器。 */
    public WorkflowPluginRegistryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                         ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 原子注册探测结果；版本变化且未确认时不覆盖当前运行版本。 */
    @Transactional
    public Registration register(String rawSource, WorkflowMarketplaceClients.MarketplaceEntry entry,
                                 WorkflowPluginWorkerClient.WorkerPackage inspected, boolean replaceExisting) {
        String source = source(rawSource);
        List<PluginIdentity> existing = jdbcTemplate.query("""
            SELECT id,package_fingerprint FROM workflow_marketplace_plugin WHERE source=? AND package_key=?
            """, (rs, row) -> new PluginIdentity(rs.getLong("id"), rs.getString("package_fingerprint")),
            source, text(entry.externalId(), 255));
        if (!existing.isEmpty() && !existing.get(0).fingerprint().equalsIgnoreCase(inspected.fingerprint())
            && !replaceExisting) {
            return new Registration(existing.get(0).id(), true, components(existing.get(0).id()));
        }
        Long pluginId;
        if (existing.isEmpty()) {
            jdbcTemplate.update("""
                INSERT INTO workflow_marketplace_plugin(source,package_key,package_version,package_fingerprint,publisher,
                    trust_level,runtime_language,install_status,compatibility_status,enabled,installed_by,installed_at)
                VALUES (?,?,?,?,?,?,?,'INSTALLED','PROBING',false,?,NOW())
                """, source, text(entry.externalId(), 255), text(entry.version(), 64), inspected.fingerprint(),
                text(entry.publisher(), 120), trust(entry.trustLevel()), language(inspected.runtimeLanguage()),
                AuthContext.require().id());
            pluginId = jdbcTemplate.queryForObject("""
                SELECT id FROM workflow_marketplace_plugin WHERE source=? AND package_key=?
                """, Long.class, source, text(entry.externalId(), 255));
        } else {
            pluginId = existing.get(0).id();
            jdbcTemplate.update("""
                UPDATE workflow_marketplace_plugin SET package_version=?,package_fingerprint=?,publisher=?,trust_level=?,
                    runtime_language=?,install_status='INSTALLED',compatibility_status='PROBING',compatibility_reason='',
                    enabled=false,installed_by=?,installed_at=NOW(),updated_at=NOW() WHERE id=?
                """, text(entry.version(), 64), inspected.fingerprint(), text(entry.publisher(), 120),
                trust(entry.trustLevel()), language(inspected.runtimeLanguage()), AuthContext.require().id(), pluginId);
            jdbcTemplate.update("""
                UPDATE workflow_marketplace_component SET compatibility_status='UNSUPPORTED',
                    compatibility_reason='REMOVED_BY_PACKAGE_UPDATE',updated_at=NOW() WHERE plugin_id=?
                """, pluginId);
        }
        for (WorkflowPluginWorkerClient.WorkerComponent component : inspected.components()) {
            upsertComponent(pluginId, component);
        }
        List<RegisteredComponent> registered = components(pluginId);
        boolean supported = registered.stream().anyMatch(item -> "SUPPORTED".equals(item.compatibilityStatus()));
        jdbcTemplate.update("""
            UPDATE workflow_marketplace_plugin SET compatibility_status=?,compatibility_reason=?,updated_at=NOW() WHERE id=?
            """, supported ? "SUPPORTED" : "PARTIAL", supported ? "" : "NO_EXECUTABLE_COMPONENT", pluginId);
        return new Registration(pluginId, false, registered);
    }

    /** 按数据库身份读取启用执行所需的固定包和组件信息。 */
    public RuntimeComponent requireRuntimeComponent(Long componentId) {
        if (componentId == null || componentId <= 0) throw new BusinessException("workflow.pluginComponentNotFound");
        List<RuntimeComponent> rows = jdbcTemplate.query("""
            SELECT c.id,p.source,p.package_key,p.package_version,p.package_fingerprint,p.enabled plugin_enabled,
                   c.external_key,c.component_type,c.schema_json,c.credential_schema_json,c.compatibility_status
            FROM workflow_marketplace_component c JOIN workflow_marketplace_plugin p ON p.id=c.plugin_id
            WHERE c.id=?
            """, (rs, row) -> runtime(rs), componentId);
        if (rows.isEmpty()) throw new BusinessException("workflow.pluginComponentNotFound");
        RuntimeComponent component = rows.get(0);
        if (!"SUPPORTED".equals(component.compatibilityStatus())) {
            throw new BusinessException("workflow.pluginComponentUnsupported");
        }
        return component;
    }

    /** 启用或停用插件包，组件执行仍会额外校验模板与连接权限。 */
    public void setEnabled(Long pluginId, boolean enabled) {
        if (jdbcTemplate.update("UPDATE workflow_marketplace_plugin SET enabled=?,updated_at=NOW() WHERE id=?",
            enabled, pluginId) != 1) throw new BusinessException("workflow.pluginComponentNotFound");
    }

    /** 返回前端配置模板和插件凭据所需的非敏感组件选项。 */
    public List<WorkflowModels.PluginComponentOption> componentOptions() {
        return jdbcTemplate.query("""
            SELECT c.id,p.source,p.package_key,p.package_version,c.external_key,c.name,c.component_type,
                   c.schema_json,c.credential_schema_json
            FROM workflow_marketplace_component c JOIN workflow_marketplace_plugin p ON p.id=c.plugin_id
            WHERE p.enabled=true AND c.compatibility_status='SUPPORTED'
            ORDER BY p.source,p.package_key,c.name
            """, (rs, row) -> new WorkflowModels.PluginComponentOption(rs.getLong("id"), rs.getString("source"),
            rs.getString("package_key"), rs.getString("package_version"), rs.getString("external_key"),
            rs.getString("name"), rs.getString("component_type"), parse(rs.getString("schema_json")),
            parse(rs.getString("credential_schema_json"))));
    }

    /** 插入或更新一个组件声明。 */
    private void upsertComponent(Long pluginId, WorkflowPluginWorkerClient.WorkerComponent component) {
        String schema = json(component.schema());
        String credentials = json(component.credentialSchema());
        String fingerprint = sha256(component.componentType() + "\n" + schema + "\n" + credentials);
        jdbcTemplate.update("""
            INSERT INTO workflow_marketplace_component(plugin_id,external_key,component_type,name,description,schema_json,
                credential_schema_json,compatibility_status,compatibility_reason,schema_fingerprint)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE component_type=VALUES(component_type),name=VALUES(name),description=VALUES(description),
                schema_json=VALUES(schema_json),credential_schema_json=VALUES(credential_schema_json),
                compatibility_status=VALUES(compatibility_status),compatibility_reason=VALUES(compatibility_reason),
                schema_fingerprint=VALUES(schema_fingerprint),updated_at=NOW()
            """, pluginId, text(component.externalId(), 255), type(component.componentType()),
            text(component.name().isBlank() ? component.externalId() : component.name(), 160),
            text(component.description(), 1000), schema, credentials, status(component.compatibilityStatus()),
            text(component.compatibilityReason(), 500), fingerprint);
    }

    /** 查询插件当前全部组件。 */
    private List<RegisteredComponent> components(Long pluginId) {
        return jdbcTemplate.query("""
            SELECT id,external_key,component_type,name,description,schema_json,credential_schema_json,
                   compatibility_status,compatibility_reason,schema_fingerprint
            FROM workflow_marketplace_component WHERE plugin_id=? ORDER BY id
            """, (rs, row) -> new RegisteredComponent(rs.getLong("id"), rs.getString("external_key"),
            rs.getString("component_type"), rs.getString("name"), rs.getString("description"),
            parse(rs.getString("schema_json")), parse(rs.getString("credential_schema_json")),
            rs.getString("compatibility_status"), rs.getString("compatibility_reason"),
            rs.getString("schema_fingerprint")), pluginId);
    }

    /** 映射不可伪造的运行时组件。 */
    private RuntimeComponent runtime(ResultSet rs) throws SQLException {
        return new RuntimeComponent(rs.getLong("id"), rs.getString("source"), rs.getString("package_key"),
            rs.getString("package_version"), rs.getString("package_fingerprint"), rs.getBoolean("plugin_enabled"),
            rs.getString("external_key"), rs.getString("component_type"), parse(rs.getString("schema_json")),
            parse(rs.getString("credential_schema_json")), rs.getString("compatibility_status"));
    }

    /** 解析可信数据库 JSON。 */
    private JsonNode parse(String value) {
        try { return objectMapper.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("插件 Schema 无法解析", exception); }
    }

    /** 序列化规范 JSON。 */
    private String json(JsonNode value) {
        return value == null || value.isMissingNode() ? "[]" : value.toString();
    }

    /** 规范市场来源。 */
    private String source(String value) {
        String source = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!List.of("N8N", "DIFY").contains(source)) throw new BusinessException("workflow.marketplaceSourceInvalid");
        return source;
    }

    /** 限制组件类型。 */
    private String type(String value) {
        String type = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!List.of("ACTION", "TOOL", "TRIGGER", "MODEL", "DATASOURCE", "AGENT_STRATEGY", "EXTENSION").contains(type)) {
            throw new BusinessException("workflow.pluginWorkerResponseInvalid");
        }
        return type;
    }

    /** 限制兼容状态。 */
    private String status(String value) {
        String status = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!List.of("SUPPORTED", "PARTIAL", "UNSUPPORTED").contains(status)) {
            throw new BusinessException("workflow.pluginWorkerResponseInvalid");
        }
        return status;
    }

    /** 限制运行语言。 */
    private String language(String value) {
        String language = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!List.of("python", "node").contains(language)) throw new BusinessException("workflow.pluginWorkerResponseInvalid");
        return language;
    }

    /** 规范市场信任等级。 */
    private String trust(String value) {
        String trust = value == null || value.isBlank() ? "COMMUNITY" : value.toUpperCase(Locale.ROOT);
        return text(trust, 32);
    }

    /** 截断非敏感市场元数据。 */
    private String text(String value, int maximum) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    /** 计算组件 Schema 指纹。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record PluginIdentity(Long id, String fingerprint) {}
    public record Registration(Long pluginId, boolean updateAvailable, List<RegisteredComponent> components) {}
    public record RegisteredComponent(Long id, String externalKey, String componentType, String name,
                                      String description, JsonNode schema, JsonNode credentialSchema,
                                      String compatibilityStatus, String compatibilityReason,
                                      String schemaFingerprint) {}
    public record RuntimeComponent(Long id, String source, String packageKey, String packageVersion,
                                   String packageFingerprint, boolean pluginEnabled, String externalKey,
                                   String componentType, JsonNode schema, JsonNode credentialSchema,
                                   String compatibilityStatus) {}
}
