package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流基线 Schema 资源测试。
 *
 * <p>历史 V1~V23 迁移已合并为单一基线，这里针对合并后的最终结构断言表、列、约束和内置节点模板。</p>
 */
class WorkflowSchemaResourceTest {
    private static final String BASELINE = "db/migration/mysql/V1__create_platform_schema.sql";

    /** 读取基线迁移脚本内容。 */
    private String baseline() throws Exception {
        return new ClassPathResource(BASELINE).getContentAsString(StandardCharsets.UTF_8);
    }

    /** 基线必须包含模板、版本、运行和节点日志，并初始化基础节点类型。 */
    @Test
    void containsVersionedWorkflowSchemaAndBuiltInNodes() throws Exception {
        String schema = baseline();

        for (String table : new String[]{"workflow_node_template", "workflow_definition", "workflow_version",
            "workflow_run", "workflow_node_run"}) assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table));
        for (String type : new String[]{"START", "END", "LLM", "HTTP", "AGENT", "CONDITION", "ITERATION", "LOOP"}) {
            assertTrue(schema.contains("'" + type + "'"));
        }
        assertTrue(schema.contains("template_snapshot_json"));
        assertTrue(schema.contains("input_encrypted"));
        assertTrue(schema.contains("output_encrypted"));
    }

    /** 定义与版本互相引用，外键必须在两表建好后补充，否则建表顺序无解。 */
    @Test
    void addsCircularVersionForeignKeysAfterTableCreation() throws Exception {
        String schema = baseline();

        int versionTable = schema.indexOf("CREATE TABLE IF NOT EXISTS workflow_version");
        int alter = schema.indexOf("ALTER TABLE workflow_definition");
        assertTrue(versionTable > 0);
        assertTrue(alter > versionTable);
        assertTrue(schema.contains("ADD CONSTRAINT fk_workflow_current_version"));
        assertTrue(schema.contains("ADD CONSTRAINT fk_workflow_published_version"));
    }

    /** 基线必须包含连接、等待和触发去重状态，并初始化全部原生扩展节点。 */
    @Test
    void containsNativeWorkflowExtensionSchemaAndNodes() throws Exception {
        String schema = baseline();

        for (String table : new String[]{"workflow_connection", "workflow_wait_state", "workflow_trigger_delivery"}) {
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table));
        }
        for (String type : WorkflowNodeTypes.ALL) {
            if (!WorkflowNodeTypes.MARKETPLACE_ONLY.contains(type)) assertTrue(schema.contains("'" + type + "'"), type);
        }
        assertTrue(schema.contains("state_encrypted"));
        assertTrue(schema.contains("uk_workflow_trigger_event"));
    }

    /** 模板来源和功能分类必须落库为列，默认来源为系统，且市场专有类型不得预置。 */
    @Test
    void declaresTemplateSourceAndFunctionalCategory() throws Exception {
        String schema = baseline();

        assertTrue(schema.contains("template_source VARCHAR(16) NOT NULL DEFAULT 'SYSTEM'"));
        assertTrue(schema.contains("functional_category VARCHAR(32) NOT NULL DEFAULT 'BASIC'"));
        assertTrue(schema.contains("idx_workflow_template_catalog"));
        for (String category : WorkflowTemplateCatalog.CATEGORIES) assertTrue(schema.contains("'" + category + "'"), category);
        for (String type : WorkflowNodeTypes.MARKETPLACE_ONLY) {
            assertTrue(WorkflowNodeTypes.ALL.contains(type), type);
            assertTrue(!schema.contains("'" + type + "'"), type);
        }
    }

    /** 内置模板必须逐行提供展示元数据，该列非空且无默认值，漏填会导致严格模式插入失败。 */
    @Test
    void seedsEveryBuiltInTemplateWithLocalizationJson() throws Exception {
        String schema = baseline();
        int insert = schema.indexOf("INSERT INTO workflow_node_template");
        assertTrue(insert > 0);
        String seed = schema.substring(insert);

        assertTrue(seed.contains("localization_json"));
        long nativeTypes = WorkflowNodeTypes.ALL.stream()
            .filter(type -> !WorkflowNodeTypes.MARKETPLACE_ONLY.contains(type)).count();
        assertEquals(nativeTypes, seed.split("'\\{}'", -1).length - 1);
        assertTrue(seed.contains("ON DUPLICATE KEY UPDATE"));
    }

    /** 基线必须包含 Key 级白名单、连接修订和多实例运行租约。 */
    @Test
    void hardensWorkflowSecurityAndExecutionState() throws Exception {
        String schema = baseline();

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS sys_api_key_workflow"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS workflow_version_connection"));
        assertTrue(schema.contains("security_revision"));
        assertTrue(schema.contains("execution_instance_id"));
        assertTrue(schema.contains("lease_expires_at"));
        assertTrue(schema.contains("log_bytes"));
    }

    /** 运行记录必须具备投递恢复和调度分发所需的期限、进度与索引。 */
    @Test
    void containsDeliveryRecoveryAndDispatchState() throws Exception {
        String schema = baseline();

        assertTrue(schema.contains("delivery_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED'"));
        assertTrue(schema.contains("idx_workflow_trigger_pending"));
        assertTrue(schema.contains("deadline_at DATETIME(6) NOT NULL"));
        assertTrue(schema.contains("progress_at"));
        assertTrue(schema.contains("idx_workflow_run_dispatch"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS workflow_schedule_state"));
    }

    /** 基线必须持久化市场身份并保证同一来源节点幂等。 */
    @Test
    void addsWorkflowMarketplaceImportMetadata() throws Exception {
        String schema = baseline();

        for (String column : new String[]{"external_key", "external_version", "external_publisher",
            "external_fingerprint", "imported_at"}) assertTrue(schema.contains(column));
        assertTrue(schema.contains("UNIQUE (template_source, external_key)"));
    }

    /** 基线必须包含知识库、向量能力状态和 RAG 系统模板。 */
    @Test
    void addsKnowledgeBaseVectorCapabilityAndRagNode() throws Exception {
        String schema = baseline();
        for (String table : new String[]{"knowledge_base", "knowledge_document", "knowledge_chunk"}) {
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table), table);
        }
        for (String column : new String[]{"vector_status", "vector_engine", "embedding_model_id", "content_encrypted"}) {
            assertTrue(schema.contains(column), column);
        }
        assertTrue(schema.contains("'RAG'"));
    }

    /** 基线必须包含纯检索、动态入库和向量化系统模板。 */
    @Test
    void addsWorkflowKnowledgeAndEmbeddingNodes() throws Exception {
        String schema = baseline();
        for (String type : new String[]{"KNOWLEDGE_RETRIEVAL", "KNOWLEDGE_UPSERT", "EMBEDDING"}) {
            assertTrue(schema.contains("'" + type + "'"), type);
        }
        assertTrue(schema.contains("'DATA_STORAGE'"));
        assertTrue(schema.contains("'AI'"));
    }

    /** 基线必须包含插件、组件、OAuth 与触发订阅状态且不含破坏性语句。 */
    @Test
    void addsMarketplacePluginRuntimeSchema() throws Exception {
        String schema = baseline();

        for (String table : new String[]{"workflow_marketplace_plugin", "workflow_marketplace_component",
            "workflow_plugin_oauth_state", "workflow_plugin_trigger_subscription"}) {
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table), table);
        }
        for (String invariant : new String[]{"package_fingerprint", "schema_fingerprint", "verifier_encrypted",
            "subscription_encrypted", "lease_expires_at", "plugin_component_id"}) {
            assertTrue(schema.contains(invariant), invariant);
        }
        assertTrue(!schema.contains("DROP TABLE"));
        assertTrue(!schema.contains("DROP COLUMN"));
    }

    /** 基线必须包含独立探测队列、固定版本唯一键、租约和缓存清理索引。 */
    @Test
    void addsMarketplacePluginProbeCache() throws Exception {
        String schema = baseline();

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS workflow_marketplace_plugin_probe"));
        for (String invariant : new String[]{"catalog_external_key", "package_fingerprint", "probe_status",
            "attempt_count", "lease_owner", "lease_expires_at", "last_accessed_at"}) {
            assertTrue(schema.contains(invariant), invariant);
        }
        assertTrue(schema.contains("UNIQUE (source, package_key, package_version)"));
    }

    /** 基线必须包含强制准入记录，且插件默认停用等待审批。 */
    @Test
    void addsPluginAdmissionControl() throws Exception {
        String schema = baseline();
        for (String invariant : new String[]{"workflow_plugin_admission", "license_name", "external_services_json",
            "data_types_json", "admission_status", "enabled_before_admission"}) {
            assertTrue(schema.contains(invariant), invariant);
        }
        assertTrue(schema.contains("admission_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'"));
        assertTrue(schema.contains("install_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'"));
        assertTrue(schema.contains("trust_level VARCHAR(32) NOT NULL DEFAULT 'COMMUNITY'"));
    }

    /** 插件组件与节点模板必须具备非空展示元数据列。 */
    @Test
    void addsWorkflowPluginLocalizationMetadata() throws Exception {
        String schema = baseline();
        assertTrue(schema.contains("workflow_marketplace_component"));
        assertTrue(schema.contains("workflow_node_template"));
        assertEquals(3, schema.split("localization_json LONGTEXT NOT NULL", -1).length - 1);
    }

    /** 运行节点必须具备可选展示身份，同时保留原始节点名列。 */
    @Test
    void addsWorkflowNodeRunLocalizationMetadata() throws Exception {
        String schema = baseline();
        assertTrue(schema.contains("workflow_node_run"));
        assertTrue(schema.contains("node_name VARCHAR(120) NOT NULL"));
        assertTrue(schema.contains("default_node_name VARCHAR(120) NOT NULL DEFAULT ''"));
    }

    /** 内置模板分类必须与目录常量保持一致，避免落库分类无法通过校验。 */
    @Test
    void seedCategoriesStayWithinCatalog() throws Exception {
        String schema = baseline();
        int insert = schema.indexOf("INSERT INTO workflow_node_template");
        String seed = schema.substring(insert);

        for (String line : seed.split("\n")) {
            if (!line.trim().startsWith("('")) continue;
            String[] parts = line.split("'SYSTEM', '");
            assertEquals(2, parts.length, line);
            String category = parts[1].substring(0, parts[1].indexOf('\''));
            assertTrue(WorkflowTemplateCatalog.CATEGORIES.contains(category), category);
        }
    }
}
