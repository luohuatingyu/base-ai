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
import java.net.URI;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 持久化固定版本插件与组件 Schema，并提供运行时不可伪造身份。 */
@Service
public class WorkflowPluginRegistryService {
    private static final Set<String> DATA_TYPES = Set.of("PUBLIC_DATA", "INTERNAL_DATA", "CUSTOMER_DATA",
        "PERSONAL_INFORMATION", "SENSITIVE_PERSONAL_INFORMATION", "CREDENTIALS", "NO_DATA");
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
            SELECT id,package_fingerprint,enabled FROM workflow_marketplace_plugin WHERE source=? AND package_key=?
            """, (rs, row) -> new PluginIdentity(rs.getLong("id"), rs.getString("package_fingerprint"),
                rs.getBoolean("enabled")),
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
            resetAdmission(pluginId, inspected);
        } else {
            pluginId = existing.get(0).id();
            jdbcTemplate.update("""
                UPDATE workflow_marketplace_plugin SET package_version=?,package_fingerprint=?,publisher=?,trust_level=?,
                    runtime_language=?,install_status='INSTALLED',compatibility_status='PROBING',compatibility_reason='',
                    enabled=?,installed_by=?,installed_at=NOW(),updated_at=NOW() WHERE id=?
                """, text(entry.version(), 64), inspected.fingerprint(), text(entry.publisher(), 120),
                trust(entry.trustLevel()), language(inspected.runtimeLanguage()),
                existing.get(0).fingerprint().equalsIgnoreCase(inspected.fingerprint()) && existing.get(0).enabled(),
                AuthContext.require().id(), pluginId);
            jdbcTemplate.update("""
                UPDATE workflow_marketplace_component SET compatibility_status='UNSUPPORTED',
                    compatibility_reason='REMOVED_BY_PACKAGE_UPDATE',updated_at=NOW() WHERE plugin_id=?
                """, pluginId);
            if (!existing.get(0).fingerprint().equalsIgnoreCase(inspected.fingerprint())) {
                resetAdmission(pluginId, inspected);
            }
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
                   c.external_key,c.component_type,c.schema_json,c.credential_schema_json,c.compatibility_status,
                   COALESCE(a.admission_status,'PENDING') admission_status
            FROM workflow_marketplace_component c JOIN workflow_marketplace_plugin p ON p.id=c.plugin_id
            LEFT JOIN workflow_plugin_admission a ON a.plugin_id=p.id
            WHERE c.id=?
            """, (rs, row) -> runtime(rs), componentId);
        if (rows.isEmpty()) throw new BusinessException("workflow.pluginComponentNotFound");
        RuntimeComponent component = rows.get(0);
        if (!"SUPPORTED".equals(component.compatibilityStatus())) {
            throw new BusinessException("workflow.pluginComponentUnsupported");
        }
        if (!component.pluginEnabled() || !"APPROVED".equals(component.admissionStatus())) {
            throw new BusinessException("workflow.pluginAdmissionRequired");
        }
        return component;
    }

    /** 启用或停用插件包；启用操作只能消费已批准准入记录。 */
    public void setEnabled(Long pluginId, boolean enabled) {
        if (enabled && jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM workflow_plugin_admission WHERE plugin_id=? AND admission_status='APPROVED'
            """, Integer.class, pluginId) != 1) throw new BusinessException("workflow.pluginAdmissionRequired");
        if (jdbcTemplate.update("UPDATE workflow_marketplace_plugin SET enabled=?,updated_at=NOW() WHERE id=?",
            enabled, pluginId) != 1) throw new BusinessException("workflow.pluginComponentNotFound");
    }

    /** 查询全部已安装插件的准入清单，不返回插件代码、凭据或执行数据。 */
    public List<WorkflowModels.PluginAdmissionView> admissions() {
        return jdbcTemplate.query("""
            SELECT p.id,p.source,p.package_key,p.package_version,p.publisher,p.enabled,
                   COALESCE(a.license_name,''),COALESCE(a.license_url,''),COALESCE(a.external_services_json,'[]'),
                   COALESCE(a.no_external_service,false),COALESCE(a.data_types_json,'[]'),COALESCE(a.data_notes,''),
                   COALESCE(a.admission_status,'PENDING'),COALESCE(a.review_note,''),a.reviewed_by,a.reviewed_at
            FROM workflow_marketplace_plugin p LEFT JOIN workflow_plugin_admission a ON a.plugin_id=p.id
            ORDER BY p.source,p.package_key
            """, (rs, row) -> admission(rs));
    }

    /** 保存管理员核对后的准入资料；任何资料变化都必须重新审批并立即停用插件。 */
    @Transactional
    public WorkflowModels.PluginAdmissionView updateAdmission(Long pluginId,
                                                               WorkflowModels.PluginAdmissionCommand command) {
        requirePlugin(pluginId);
        AdmissionFields fields = validate(command);
        jdbcTemplate.update("""
            INSERT INTO workflow_plugin_admission(plugin_id,license_name,license_url,external_services_json,
                no_external_service,data_types_json,data_notes,admission_status,review_note,reviewed_by,reviewed_at)
            VALUES (?,?,?,?,?,?,?,'PENDING','',NULL,NULL)
            ON DUPLICATE KEY UPDATE license_name=VALUES(license_name),license_url=VALUES(license_url),
                external_services_json=VALUES(external_services_json),no_external_service=VALUES(no_external_service),
                data_types_json=VALUES(data_types_json),data_notes=VALUES(data_notes),admission_status='PENDING',
                review_note='',reviewed_by=NULL,reviewed_at=NULL,updated_at=NOW()
            """, pluginId, fields.licenseName(), fields.licenseUrl(), json(fields.externalServices()),
            fields.noExternalService(), json(fields.dataTypes()), fields.dataNotes());
        jdbcTemplate.update("UPDATE workflow_marketplace_plugin SET enabled=false,updated_at=NOW() WHERE id=?", pluginId);
        return admission(pluginId);
    }

    /** 批准或拒绝一份完整准入资料，并原子同步插件启用状态。 */
    @Transactional
    public WorkflowModels.PluginAdmissionView reviewAdmission(Long pluginId,
                                                               WorkflowModels.PluginAdmissionReviewCommand command) {
        if (command == null || command.approved() == null) throw new BusinessException("workflow.pluginAdmissionInvalid");
        WorkflowModels.PluginAdmissionView current = admission(pluginId);
        if (command.approved()) {
            validate(new WorkflowModels.PluginAdmissionCommand(current.licenseName(), current.licenseUrl(),
                current.externalServices(), current.noExternalService(), current.dataTypes(), current.dataNotes()));
            if (jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM workflow_marketplace_plugin WHERE id=? AND compatibility_status='SUPPORTED'
                """, Integer.class, pluginId) != 1) throw new BusinessException("workflow.pluginComponentUnsupported");
        }
        String status = command.approved() ? "APPROVED" : "REJECTED";
        String note = text(command.reviewNote(), 1000);
        jdbcTemplate.update("""
            UPDATE workflow_plugin_admission SET admission_status=?,review_note=?,reviewed_by=?,reviewed_at=NOW(),
                updated_at=NOW() WHERE plugin_id=?
            """, status, note, AuthContext.require().id(), pluginId);
        jdbcTemplate.update("UPDATE workflow_marketplace_plugin SET enabled=?,updated_at=NOW() WHERE id=?",
            command.approved(), pluginId);
        return admission(pluginId);
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
            parse(rs.getString("credential_schema_json")), rs.getString("compatibility_status"),
            rs.getString("admission_status"));
    }

    /** 新包首次注册或版本变化时写入可信预填值并清除旧审批。 */
    private void resetAdmission(Long pluginId, WorkflowPluginWorkerClient.WorkerPackage inspected) {
        List<WorkflowModels.PluginExternalService> services = inspected.externalServices() == null ? List.of()
            : inspected.externalServices().stream().map(item -> new WorkflowModels.PluginExternalService(
                text(item.name().isBlank() ? item.domain() : item.name(), 160), domain(item.domain()))).toList();
        List<String> inferred = inspected.components().stream().anyMatch(item -> item.credentialSchema() != null
            && item.credentialSchema().isArray() && !item.credentialSchema().isEmpty()) ? List.of("CREDENTIALS") : List.of();
        jdbcTemplate.update("""
            INSERT INTO workflow_plugin_admission(plugin_id,license_name,license_url,external_services_json,
                no_external_service,data_types_json,admission_status,review_note,reviewed_by,reviewed_at)
            VALUES (?,?,?,?,false,?,'PENDING','',NULL,NULL)
            ON DUPLICATE KEY UPDATE license_name=VALUES(license_name),license_url=VALUES(license_url),
                external_services_json=VALUES(external_services_json),no_external_service=false,
                data_types_json=VALUES(data_types_json),data_notes='',admission_status='PENDING',review_note='',
                reviewed_by=NULL,reviewed_at=NULL,updated_at=NOW()
            """, pluginId, text(inspected.licenseName(), 160), https(inspected.licenseUrl()), json(services), json(inferred));
    }

    /** 读取一份准入记录并验证插件存在。 */
    private WorkflowModels.PluginAdmissionView admission(Long pluginId) {
        return admissions().stream().filter(item -> item.pluginId().equals(pluginId)).findFirst()
            .orElseThrow(() -> new BusinessException("workflow.pluginComponentNotFound"));
    }

    /** 映射准入清单行。 */
    private WorkflowModels.PluginAdmissionView admission(ResultSet rs) throws SQLException {
        Long reviewedBy = rs.getObject(15) == null ? null : rs.getLong(15);
        LocalDateTime reviewedAt = rs.getTimestamp(16) == null ? null : rs.getTimestamp(16).toLocalDateTime();
        return new WorkflowModels.PluginAdmissionView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(7), rs.getString(8), services(rs.getString(9)), rs.getBoolean(10),
            strings(rs.getString(11)), rs.getString(12), rs.getString(13), rs.getString(14), reviewedBy,
            reviewedAt, rs.getBoolean(6));
    }

    /** 验证并规范管理员填写的准入资料。 */
    private AdmissionFields validate(WorkflowModels.PluginAdmissionCommand command) {
        if (command == null || command.licenseName() == null || command.licenseName().isBlank()
            || command.noExternalService() == null) throw new BusinessException("workflow.pluginAdmissionInvalid");
        List<WorkflowModels.PluginExternalService> services = command.externalServices() == null ? List.of()
            : command.externalServices().stream().map(item -> {
                if (item == null) throw new BusinessException("workflow.pluginAdmissionInvalid");
                String domain = domain(item.domain());
                String name = item.name() == null || item.name().isBlank() ? domain : item.name();
                return new WorkflowModels.PluginExternalService(text(name, 160), domain);
            }).distinct().toList();
        if (command.noExternalService() != services.isEmpty()) {
            throw new BusinessException("workflow.pluginAdmissionInvalid");
        }
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (command.dataTypes() != null) command.dataTypes().forEach(value -> types.add(
            value == null ? "" : value.trim().toUpperCase(Locale.ROOT)));
        if (types.isEmpty() || !DATA_TYPES.containsAll(types)
            || types.contains("NO_DATA") && types.size() != 1) throw new BusinessException("workflow.pluginAdmissionInvalid");
        return new AdmissionFields(text(command.licenseName(), 160), https(command.licenseUrl()), services,
            command.noExternalService(), List.copyOf(types), text(command.dataNotes(), 1000));
    }

    /** 确认插件主记录存在。 */
    private void requirePlugin(Long pluginId) {
        if (pluginId == null || pluginId <= 0 || jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workflow_marketplace_plugin WHERE id=?", Integer.class, pluginId) != 1) {
            throw new BusinessException("workflow.pluginComponentNotFound");
        }
    }

    /** 只接受不含凭据、查询或片段的 HTTPS 许可证地址。 */
    private String https(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return text(uri.toString(), 500);
        } catch (Exception exception) { throw new BusinessException("workflow.pluginAdmissionInvalid"); }
    }

    /** 规范外部服务域名并拒绝 URL、端口、路径和通配符。 */
    private String domain(String value) {
        String domain = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!domain.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
            throw new BusinessException("workflow.pluginAdmissionInvalid");
        }
        return domain;
    }

    /** 解析受信数据库中的外部服务 JSON。 */
    private List<WorkflowModels.PluginExternalService> services(String value) {
        try {
            JsonNode root = parse(value);
            List<WorkflowModels.PluginExternalService> result = new ArrayList<>();
            for (JsonNode item : root) result.add(new WorkflowModels.PluginExternalService(
                item.path("name").asText(""), item.path("domain").asText("")));
            return List.copyOf(result);
        } catch (RuntimeException exception) { throw new IllegalStateException("插件准入服务无法解析", exception); }
    }

    /** 解析受信数据库中的字符串数组。 */
    private List<String> strings(String value) {
        try {
            List<String> result = new ArrayList<>();
            for (JsonNode item : parse(value)) result.add(item.asText());
            return List.copyOf(result);
        } catch (RuntimeException exception) { throw new IllegalStateException("插件准入数据类型无法解析", exception); }
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

    /** 序列化受控准入值。 */
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("插件准入资料无法序列化", exception); }
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

    private record PluginIdentity(Long id, String fingerprint, boolean enabled) {}
    private record AdmissionFields(String licenseName, String licenseUrl,
                                   List<WorkflowModels.PluginExternalService> externalServices,
                                   boolean noExternalService, List<String> dataTypes, String dataNotes) {}
    public record Registration(Long pluginId, boolean updateAvailable, List<RegisteredComponent> components) {}
    public record RegisteredComponent(Long id, String externalKey, String componentType, String name,
                                      String description, JsonNode schema, JsonNode credentialSchema,
                                      String compatibilityStatus, String compatibilityReason,
                                      String schemaFingerprint) {}
    public record RuntimeComponent(Long id, String source, String packageKey, String packageVersion,
                                   String packageFingerprint, boolean pluginEnabled, String externalKey,
                                   String componentType, JsonNode schema, JsonNode credentialSchema,
                                   String compatibilityStatus, String admissionStatus) {
        /** 兼容现有测试与不关心准入状态的构造调用。 */
        public RuntimeComponent(Long id, String source, String packageKey, String packageVersion,
                                String packageFingerprint, boolean pluginEnabled, String externalKey,
                                String componentType, JsonNode schema, JsonNode credentialSchema,
                                String compatibilityStatus) {
            this(id, source, packageKey, packageVersion, packageFingerprint, pluginEnabled, externalKey,
                componentType, schema, credentialSchema, compatibilityStatus, "APPROVED");
        }
    }
}
