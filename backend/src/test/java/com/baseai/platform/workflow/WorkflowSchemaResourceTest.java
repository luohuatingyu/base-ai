package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowSchemaResourceTest {
    private static final Set<String> POST_CATALOG_MIGRATION_TYPES = Set.of(
        "RAG", "KNOWLEDGE_RETRIEVAL", "KNOWLEDGE_UPSERT", "EMBEDDING");

    /** MySQL V4 必须包含模板、版本、运行和节点日志，并初始化全部节点类型。 */
    @Test
    void containsVersionedWorkflowSchemaAndBuiltInNodes() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V4__create_workflow_schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        for (String table : new String[]{"workflow_node_template", "workflow_definition", "workflow_version",
            "workflow_run", "workflow_node_run"}) assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table));
        for (String type : new String[]{"START", "END", "LLM", "HTTP", "AGENT", "CONDITION", "ITERATION", "LOOP"}) {
            assertTrue(schema.contains("'" + type + "'"));
        }
        assertTrue(schema.contains("template_snapshot_json"));
        assertTrue(schema.contains("input_encrypted"));
        assertTrue(schema.contains("output_encrypted"));
    }

    /** MySQL V5 必须兼容仍保留旧 API Key 限流列的历史数据库。 */
    @Test
    void relaxesLegacyApiKeyRateLimitColumn() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V5__relax_legacy_api_key_rate_limit.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("MODIFY COLUMN rate_limit_per_minute INT NULL"));
    }

    /** MySQL V6 必须包含连接、等待和触发去重状态，并初始化全部新增原生节点。 */
    @Test
    void containsNativeWorkflowExtensionSchemaAndNodes() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V6__extend_native_workflow_nodes.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        for (String table : new String[]{"workflow_connection", "workflow_wait_state", "workflow_trigger_delivery"}) {
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS " + table));
        }
        for (String type : WorkflowNodeTypes.ALL) {
            if (!Set.of("START", "END", "LLM", "HTTP", "AGENT", "CONDITION", "ITERATION", "LOOP").contains(type)
                && !WorkflowNodeTypes.MARKETPLACE_ONLY.contains(type) && !POST_CATALOG_MIGRATION_TYPES.contains(type)) {
                assertTrue(schema.contains("'" + type + "'"), type);
            }
        }
        assertTrue(schema.contains("state_encrypted"));
        assertTrue(schema.contains("uk_workflow_trigger_event"));
    }

    /** MySQL V8 必须把模板来源约束为 Base AI 原生或用户自定义。 */
    @Test
    void normalizesNativeWorkflowTemplateSources() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V8__normalize_native_workflow_template_sources.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("system_template = b'1' THEN 'SYSTEM' ELSE 'CUSTOM'"));
        assertTrue(schema.contains("DEFAULT 'CUSTOM'"));
    }

    /** MySQL V9 必须恢复系统、n8n、Dify 三种后台可配置来源并兼容已执行 V8 的数据库。 */
    @Test
    void restoresConfigurableWorkflowTemplateSources() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V9__restore_configurable_workflow_template_sources.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("DEFAULT 'SYSTEM'"));
        assertTrue(schema.contains("NOT IN ('SYSTEM', 'N8N', 'DIFY')"));
    }

    /** MySQL V7 必须增加来源和功能分类，并将历史模板准确回填为系统来源。 */
    @Test
    void categorizesExistingWorkflowNodeTemplates() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V7__categorize_workflow_node_templates.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("template_source VARCHAR(16) NOT NULL DEFAULT 'SYSTEM'"));
        assertTrue(schema.contains("functional_category VARCHAR(32) NOT NULL DEFAULT 'BASIC'"));
        assertTrue(schema.contains("SET template_source = 'SYSTEM'"));
        for (String category : WorkflowTemplateCatalog.CATEGORIES) assertTrue(schema.contains("'" + category + "'"), category);
        for (String type : WorkflowNodeTypes.ALL) {
            if (!WorkflowNodeTypes.MARKETPLACE_ONLY.contains(type) && !POST_CATALOG_MIGRATION_TYPES.contains(type)) {
                assertTrue(schema.contains("'" + type + "'"), type);
            }
        }
        for (String type : WorkflowNodeTypes.MARKETPLACE_ONLY) {
            assertTrue(WorkflowNodeTypes.ALL.contains(type), type);
            assertTrue(!schema.contains("'" + type + "'"), type);
        }
    }

    /** MySQL V10 必须增加 Key 级白名单、连接修订和多实例运行租约。 */
    @Test
    void hardensWorkflowSecurityAndExecutionState() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V10__harden_workflow_security_and_execution.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS sys_api_key_workflow"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS workflow_version_connection"));
        assertTrue(schema.contains("security_revision"));
        assertTrue(schema.contains("execution_instance_id"));
        assertTrue(schema.contains("lease_expires_at"));
        assertTrue(schema.contains("log_bytes"));
    }

    /** MySQL V11 必须持久化市场身份并保证同一来源节点幂等。 */
    @Test
    void addsWorkflowMarketplaceImportMetadata() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V11__add_workflow_marketplace_imports.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        for (String column : new String[]{"external_key", "external_version", "external_publisher",
            "external_fingerprint", "imported_at"}) assertTrue(schema.contains(column));
        assertTrue(schema.contains("UNIQUE (template_source, external_key)"));
    }

    /** MySQL V12 必须增加知识库、向量能力状态和 RAG 系统模板。 */
    @Test
    void addsKnowledgeBaseVectorCapabilityAndRagNode() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V12__add_knowledge_base_rag.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        for (String table : new String[]{"knowledge_base", "knowledge_document", "knowledge_chunk"}) {
            assertTrue(schema.contains("CREATE TABLE " + table), table);
        }
        for (String column : new String[]{"vector_status", "vector_engine", "embedding_model_id", "content_encrypted"}) {
            assertTrue(schema.contains(column), column);
        }
        assertTrue(schema.contains("'RAG'"));
    }

    /** MySQL V13 必须增加纯检索和动态入库系统模板。 */
    @Test
    void addsWorkflowKnowledgeRetrievalAndUpsertNodes() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V13__add_workflow_knowledge_nodes.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(schema.contains("'KNOWLEDGE_RETRIEVAL'"));
        assertTrue(schema.contains("'KNOWLEDGE_UPSERT'"));
        assertTrue(schema.contains("'DATA_STORAGE'"));
    }

    /** MySQL V14 必须幂等增加原生向量化节点模板。 */
    @Test
    void addsWorkflowEmbeddingNode() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V14__add_workflow_embedding_node.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("'EMBEDDING'"));
        assertTrue(schema.contains("'AI'"));
        assertTrue(schema.contains("ON DUPLICATE KEY UPDATE"));
    }

    /** MySQL V15 必须增加插件、组件、OAuth 与触发订阅状态且仅做增量扩展。 */
    @Test
    void addsMarketplacePluginRuntimeSchema() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V15__add_marketplace_plugin_runtime.sql")
            .getContentAsString(StandardCharsets.UTF_8);

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

    /** MySQL V16 必须增加独立探测队列、固定版本唯一键、租约和缓存清理索引。 */
    @Test
    void addsMarketplacePluginProbeCache() throws Exception {
        String schema = new ClassPathResource("db/migration/mysql/V16__add_marketplace_plugin_probe_cache.sql")
            .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS workflow_marketplace_plugin_probe"));
        for (String invariant : new String[]{"catalog_external_key", "package_fingerprint", "probe_status",
            "attempt_count", "lease_owner", "lease_expires_at", "last_accessed_at"}) {
            assertTrue(schema.contains(invariant), invariant);
        }
        assertTrue(schema.contains("UNIQUE (source, package_key, package_version)"));
        assertTrue(!schema.contains("DROP TABLE"));
        assertTrue(!schema.contains("DROP COLUMN"));
    }
}
