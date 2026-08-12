package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowPluginRegistryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private WorkflowPluginRegistryService service;

    /** 创建与 Flyway 插件注册表关键约束一致的隔离数据库。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-plugin-registry-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_marketplace_plugin(id BIGINT AUTO_INCREMENT PRIMARY KEY,source VARCHAR(16),package_key VARCHAR(255),
            package_version VARCHAR(64),package_fingerprint CHAR(64),publisher VARCHAR(120),trust_level VARCHAR(32),runtime_language VARCHAR(16),
            install_status VARCHAR(24),compatibility_status VARCHAR(24),compatibility_reason VARCHAR(500) DEFAULT '',enabled BOOLEAN,
            installed_by BIGINT,installed_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(source,package_key))
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_marketplace_component(id BIGINT AUTO_INCREMENT PRIMARY KEY,plugin_id BIGINT,external_key VARCHAR(255),
            component_type VARCHAR(32),name VARCHAR(160),description VARCHAR(1000),schema_json CLOB,credential_schema_json CLOB,
            compatibility_status VARCHAR(24),compatibility_reason VARCHAR(500),schema_fingerprint CHAR(64),
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(plugin_id,external_key))
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_plugin_admission(plugin_id BIGINT PRIMARY KEY,license_name VARCHAR(160) DEFAULT '',license_url VARCHAR(500) DEFAULT '',
            external_services_json CLOB,data_types_json CLOB,no_external_service BOOLEAN DEFAULT false,data_notes VARCHAR(1000) DEFAULT '',
            admission_status VARCHAR(24) DEFAULT 'PENDING',review_note VARCHAR(1000) DEFAULT '',reviewed_by BIGINT,reviewed_at TIMESTAMP,
            enabled_before_admission BOOLEAN DEFAULT false,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        service = new WorkflowPluginRegistryService(jdbcTemplate, mapper);
        AuthContext.set(new AuthUser(7L, "user", Set.of("USER"), Set.of(), AuthenticationType.TOKEN, null, null));
    }

    /** 清理线程登录态。 */
    @AfterEach
    void tearDown() { AuthContext.clear(); }

    /** 受支持组件应固定包摘要和 Schema，并只在启用后提供给前端。 */
    @Test
    void registersPinsAndEnablesSupportedComponent() {
        var registration = service.register("N8N", entry("pkg", "1"), inspected("1", "a".repeat(64), "SUPPORTED"), false);

        assertFalse(registration.updateAvailable());
        assertEquals(1, registration.components().size());
        assertTrue(service.componentOptions().isEmpty());
        service.updateAdmission(registration.pluginId(), new WorkflowModels.PluginAdmissionCommand("MIT", "",
            List.of(), true, List.of("NO_DATA"), ""));
        service.reviewAdmission(registration.pluginId(), new WorkflowModels.PluginAdmissionReviewCommand(true, "approved"));
        assertEquals("query", service.componentOptions().get(0).parameterSchema().get(0).path("name").asText());
        assertEquals("a".repeat(64), service.requireRuntimeComponent(registration.components().get(0).id()).packageFingerprint());
    }

    /** 插件必须先补全资料并获批；资料变更或包更新会立即重置和停用。 */
    @Test
    void enforcesAdmissionAndResetsApprovalAfterMetadataChange() {
        var registration = service.register("N8N", entry("pkg", "1"), inspected("1", "a".repeat(64), "SUPPORTED"), false);
        Long componentId = registration.components().get(0).id();
        assertEquals("workflow.pluginAdmissionRequired", assertThrows(BusinessException.class,
            () -> service.requireRuntimeComponent(componentId)).getMessageKey());
        assertEquals("workflow.pluginAdmissionRequired", assertThrows(BusinessException.class,
            () -> service.setEnabled(registration.pluginId(), true)).getMessageKey());
        assertThrows(BusinessException.class, () -> service.updateAdmission(registration.pluginId(),
            new WorkflowModels.PluginAdmissionCommand("", "http://bad", List.of(), false, List.of("NO_DATA", "PUBLIC_DATA"), "")));

        service.updateAdmission(registration.pluginId(), new WorkflowModels.PluginAdmissionCommand("MIT", "https://spdx.org/licenses/MIT.html",
            List.of(new WorkflowModels.PluginExternalService(null, "api.example.com")), false,
            List.of("INTERNAL_DATA", "CREDENTIALS"), "reviewed"));
        service.reviewAdmission(registration.pluginId(), new WorkflowModels.PluginAdmissionReviewCommand(true, "approved"));
        assertEquals("api.example.com", service.admissions().get(0).externalServices().get(0).name());
        assertEquals("APPROVED", service.requireRuntimeComponent(componentId).admissionStatus());

        service.register("N8N", entry("pkg", "1"), inspected("1", "a".repeat(64), "SUPPORTED"), false);
        assertEquals("APPROVED", service.requireRuntimeComponent(componentId).admissionStatus());

        service.reviewAdmission(registration.pluginId(), new WorkflowModels.PluginAdmissionReviewCommand(false, "rejected"));
        assertEquals("REJECTED", service.admissions().get(0).admissionStatus());
        assertFalse(service.admissions().get(0).pluginEnabled());

        service.register("N8N", entry("pkg", "2"), inspected("2", "b".repeat(64), "SUPPORTED"), true);
        assertEquals("PENDING", service.admissions().get(0).admissionStatus());
        assertFalse(service.admissions().get(0).pluginEnabled());
    }

    /** 新包摘要必须要求显式替换，部分兼容组件不可进入运行时。 */
    @Test
    void requiresExplicitReplacementAndRejectsPartialRuntime() {
        service.register("DIFY", entry("pkg", "1"), inspected("1", "a".repeat(64), "PARTIAL"), false);
        var pending = service.register("DIFY", entry("pkg", "2"), inspected("2", "b".repeat(64), "SUPPORTED"), false);

        assertTrue(pending.updateAvailable());
        Long componentId = pending.components().get(0).id();
        assertEquals("workflow.pluginComponentUnsupported", assertThrows(BusinessException.class,
            () -> service.requireRuntimeComponent(componentId)).getMessageKey());
    }

    /** 构造最小市场条目。 */
    private WorkflowMarketplaceClients.MarketplaceEntry entry(String id, String version) {
        return new WorkflowMarketplaceClients.MarketplaceEntry(id, "Plugin", "Description", version,
            "publisher", "tool", "verified", mapper.createObjectNode());
    }

    /** 构造包含一个动作的 Worker 探测结果。 */
    private WorkflowPluginWorkerClient.WorkerPackage inspected(String version, String fingerprint, String status) {
        var component = new WorkflowPluginWorkerClient.WorkerComponent("action", "Action", "Description", "ACTION",
            mapper.createArrayNode().add(mapper.createObjectNode().put("name", "query").put("required", true)),
            mapper.createArrayNode().add(mapper.createObjectNode().put("name", "apiKey").put("type", "secret-input")),
            "action.js", status, status.equals("SUPPORTED") ? "" : "ABI_MISSING");
        return new WorkflowPluginWorkerClient.WorkerPackage("N8N", "pkg", version, fingerprint, "node", 4,
            "MIT", "https://spdx.org/licenses/MIT.html", List.of(new WorkflowPluginWorkerClient.WorkerExternalService(
            "Example", "api.example.com")), List.of(component));
    }
}
