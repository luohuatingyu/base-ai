package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class WorkflowPluginProbeServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private WorkflowMarketplaceClients clients;
    private WorkflowPluginWorkerClient workers;
    private ThreadPoolTaskExecutor executor;
    private WorkflowPluginProbeService service;

    /** 创建与 V16 关键约束一致的隔离探测队列表。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:plugin-probe;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
            CREATE TABLE workflow_marketplace_plugin_probe(
              id BIGINT AUTO_INCREMENT PRIMARY KEY,source VARCHAR(16) NOT NULL,catalog_external_key VARCHAR(255) NOT NULL,
              package_key VARCHAR(255) NOT NULL,package_version VARCHAR(64) NOT NULL,package_fingerprint CHAR(64),
              probe_status VARCHAR(24) NOT NULL,compatibility_status VARCHAR(24) NOT NULL,
              compatibility_reason VARCHAR(500) NOT NULL DEFAULT '',result_json CLOB,attempt_count INT NOT NULL DEFAULT 0,
              next_attempt_at TIMESTAMP NOT NULL,lease_owner VARCHAR(120),lease_expires_at TIMESTAMP,
              last_accessed_at TIMESTAMP NOT NULL,probed_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(source,package_key,package_version))
            """);
        jdbc.execute("CREATE TABLE workflow_marketplace_plugin(id BIGINT PRIMARY KEY,package_fingerprint CHAR(64))");
        clients = mock(WorkflowMarketplaceClients.class);
        workers = mock(WorkflowPluginWorkerClient.class);
        PlatformProperties properties = new PlatformProperties();
        properties.getWorkflow().setMarketplaceProbeConcurrency(1);
        properties.getWorkflow().setMarketplaceProbeQueueCapacity(4);
        properties.getWorkflow().setMarketplaceProbeMaxAttempts(2);
        executor = new WorkflowPluginProbeExecutorConfig().workflowPluginProbeExecutor(properties);
        service = new WorkflowPluginProbeService(jdbc, mapper, clients, workers, executor, properties);
    }

    /** 关闭正式线程池，避免测试结束后遗留后台线程。 */
    @AfterEach
    void tearDown() { executor.shutdown(); }

    /** 默认线程池应允许四个市场包并发探测，缩短当前页排队时间。 */
    @Test
    void usesFourProbeWorkersByDefault() {
        ThreadPoolTaskExecutor defaultExecutor = new WorkflowPluginProbeExecutorConfig()
            .workflowPluginProbeExecutor(new PlatformProperties());
        try {
            assertEquals(4, defaultExecutor.getCorePoolSize());
            assertEquals(4, defaultExecutor.getMaxPoolSize());
        } finally {
            defaultExecutor.shutdown();
        }
    }

    /** 当前页重复读取只应创建一个固定版本任务，并异步持久化兼容结果。 */
    @Test
    void queuesOnceAndCompletesProbeAsynchronously() throws Exception {
        var entry = n8nEntry();
        String fingerprint = "a".repeat(64);
        when(clients.findN8n(entry.externalId())).thenReturn(Optional.of(entry));
        when(clients.downloadN8nPackage(entry)).thenReturn(
            new WorkflowMarketplaceClients.PackageDownload(new byte[]{1}, fingerprint));
        when(workers.inspect("N8N", "n8n-nodes-example", "1.2.3", new byte[]{1}, fingerprint))
            .thenReturn(workerPackage(fingerprint, "SUPPORTED"));

        assertEquals("QUEUED", service.snapshot("N8N", entry, true).probeStatus());
        service.snapshot("N8N", entry, true);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_marketplace_plugin_probe", Integer.class));

        service.dispatch();
        awaitStatus("COMPLETE");
        WorkflowPluginProbeService.ProbeSnapshot completed = service.snapshot("N8N", entry, false);
        assertEquals("SUPPORTED", completed.compatibilityStatus());
        assertEquals(fingerprint, service.requireCompleted("N8N", entry).fingerprint());
        verify(workers).inspect("N8N", "n8n-nodes-example", "1.2.3", new byte[]{1}, fingerprint);
    }

    /** 数据库已承担排队职责时，只允许立即可执行的任务进入 PROBING。 */
    @Test
    void claimsOnlyImmediatelyExecutableTasks() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String fingerprint = "7".repeat(64);
        for (int index = 0; index < 3; index++) {
            var entry = n8nEntry("node-" + index, "n8n-nodes-example-" + index);
            when(clients.findN8n(entry.externalId())).thenReturn(Optional.of(entry));
            when(clients.downloadN8nPackage(entry)).thenReturn(
                new WorkflowMarketplaceClients.PackageDownload(new byte[]{1}, fingerprint));
            service.snapshot("N8N", entry, true);
        }
        when(workers.inspect(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return workerPackage(fingerprint, "SUPPORTED");
        });

        service.dispatch();
        started.await(1, TimeUnit.SECONDS);

        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM workflow_marketplace_plugin_probe WHERE probe_status='PROBING'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM workflow_marketplace_plugin_probe WHERE probe_status='QUEUED'", Integer.class));
        release.countDown();
        for (int attempt = 0; attempt < 100; attempt++) {
            if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_marketplace_plugin_probe WHERE probe_status='PROBING'", Integer.class) == 0) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM workflow_marketplace_plugin_probe WHERE probe_status='PROBING'", Integer.class));
    }

    /** Dify 已固定包与版本后应直接下载，不得为每个后台任务重复执行市场搜索。 */
    @Test
    void probesPinnedDifyPackageWithoutCatalogResearch() throws Exception {
        var entry = difyEntry();
        byte[] archive = new byte[]{4, 2};
        String fingerprint = WorkflowNodeMarketplaceService.sha256Bytes(archive);
        when(clients.downloadDifyPackage(entry.externalId(), entry.version())).thenReturn(archive);
        when(workers.inspect("DIFY", entry.externalId(), entry.version(), archive, fingerprint))
            .thenReturn(new WorkflowPluginWorkerClient.WorkerPackage("DIFY", entry.externalId(), entry.version(),
                fingerprint, "python", workerPackage(fingerprint, "SUPPORTED").components()));

        service.snapshot("DIFY", entry, true);
        service.dispatch();
        awaitStatus("COMPLETE");

        verify(clients, never()).findDify(entry.externalId());
        verify(clients).downloadDifyPackage(entry.externalId(), entry.version());
    }

    /** 安全拒绝必须终止重试，并且导入读取接口不得退化为同步探测。 */
    @Test
    void persistsTerminalRejectionAndRefusesImport() throws Exception {
        var entry = n8nEntry();
        when(clients.findN8n(entry.externalId())).thenReturn(Optional.of(entry));
        when(clients.downloadN8nPackage(entry)).thenReturn(
            new WorkflowMarketplaceClients.PackageDownload(new byte[]{1}, "b".repeat(64)));
        when(workers.inspect(any(), any(), any(), any(), any()))
            .thenThrow(new BusinessException("workflow.pluginWorkerRejected", "ARCHIVE_PATH_INVALID"));

        service.snapshot("N8N", entry, true);
        service.dispatch();
        awaitStatus("REJECTED");

        WorkflowPluginProbeService.ProbeSnapshot rejected = service.snapshot("N8N", entry, false);
        assertEquals("UNSUPPORTED", rejected.compatibilityStatus());
        assertEquals("PACKAGE_ARCHIVE_INVALID", rejected.compatibilityReason());
        assertThrows(BusinessException.class, () -> service.requireCompleted("N8N", entry));
        assertEquals(1, jdbc.queryForObject(
            "SELECT attempt_count FROM workflow_marketplace_plugin_probe", Integer.class));
    }

    /** 未入队或仍在排队的版本都不得被导入读取接口接受。 */
    @Test
    void rejectsMissingAndQueuedProbeResults() {
        var entry = n8nEntry();
        assertEquals("NOT_PROBED", service.snapshot("N8N", entry, false).probeStatus());
        assertThrows(BusinessException.class, () -> service.requireCompleted("N8N", entry));
        service.snapshot("N8N", entry, true);
        assertThrows(BusinessException.class, () -> service.requireCompleted("N8N", entry));
    }

    /** 混合、全兼容和全不兼容组件必须聚合为稳定包级结论。 */
    @ParameterizedTest
    @MethodSource("compatibilityCases")
    void summarizesComponentCompatibility(List<String> componentStatuses, String expected) throws Exception {
        var entry = n8nEntry();
        String fingerprint = "c".repeat(64);
        when(clients.findN8n(entry.externalId())).thenReturn(Optional.of(entry));
        when(clients.downloadN8nPackage(entry)).thenReturn(
            new WorkflowMarketplaceClients.PackageDownload(new byte[]{1}, fingerprint));
        when(workers.inspect(any(), any(), any(), any(), any())).thenReturn(
            workerPackage(fingerprint, componentStatuses));

        service.snapshot("N8N", entry, true);
        service.dispatch();
        awaitStatus("COMPLETE");

        assertEquals(expected, service.snapshot("N8N", entry, false).compatibilityStatus());
    }

    /** 全部组件缺少声明式路由能力时应保存可公开原因，而不是笼统不可执行。 */
    @Test
    void summarizesUnsupportedRoutingReason() throws Exception {
        var entry = n8nEntry();
        String fingerprint = "6".repeat(64);
        when(clients.findN8n(entry.externalId())).thenReturn(Optional.of(entry));
        when(clients.downloadN8nPackage(entry)).thenReturn(
            new WorkflowMarketplaceClients.PackageDownload(new byte[]{1}, fingerprint));
        WorkflowPluginWorkerClient.WorkerComponent failed = new WorkflowPluginWorkerClient.WorkerComponent(
            "action", "Action", "", "ACTION", mapper.createArrayNode(), mapper.createArrayNode(), "node.js",
            "PARTIAL", "DECLARATIVE_ROUTING_NOT_IMPLEMENTED");
        when(workers.inspect(any(), any(), any(), any(), any())).thenReturn(
            new WorkflowPluginWorkerClient.WorkerPackage("N8N", "pkg", "1", fingerprint, "node", 2,
                List.of(failed)));

        service.snapshot("N8N", entry, true);
        service.dispatch();
        awaitStatus("COMPLETE");

        WorkflowPluginProbeService.ProbeSnapshot snapshot = service.snapshot("N8N", entry, false);
        assertEquals("UNSUPPORTED", snapshot.compatibilityStatus());
        assertEquals("ROUTING_UNSUPPORTED", snapshot.compatibilityReason());
    }

    /** 短暂 Worker 故障按上限重试，耗尽后收敛为 FAILED。 */
    @Test
    void retriesTransientFailureUntilAttemptLimit() throws Exception {
        var entry = n8nEntry();
        when(clients.findN8n(entry.externalId())).thenReturn(Optional.of(entry));
        when(clients.downloadN8nPackage(entry)).thenReturn(
            new WorkflowMarketplaceClients.PackageDownload(new byte[]{1}, "d".repeat(64)));
        when(workers.inspect(any(), any(), any(), any(), any()))
            .thenThrow(new BusinessException("workflow.pluginWorkerUnavailable"));

        service.snapshot("N8N", entry, true);
        service.dispatch();
        awaitStatus("QUEUED");
        jdbc.update("UPDATE workflow_marketplace_plugin_probe SET next_attempt_at=CURRENT_TIMESTAMP");
        service.dispatch();
        awaitStatus("FAILED");

        assertEquals(2, jdbc.queryForObject(
            "SELECT attempt_count FROM workflow_marketplace_plugin_probe", Integer.class));
    }

    /** Worker 返回的依赖安装故障必须进入重试而非永久不兼容。 */
    @Test
    void retriesDependencyInstallationFailure() throws Exception {
        var entry = n8nEntry();
        String fingerprint = "9".repeat(64);
        when(clients.findN8n(entry.externalId())).thenReturn(Optional.of(entry));
        when(clients.downloadN8nPackage(entry)).thenReturn(
            new WorkflowMarketplaceClients.PackageDownload(new byte[]{1}, fingerprint));
        WorkflowPluginWorkerClient.WorkerComponent failed = new WorkflowPluginWorkerClient.WorkerComponent(
            "action", "Action", "", "ACTION", mapper.createArrayNode(), mapper.createArrayNode(), "node.js",
            "PARTIAL", "DEPENDENCY_INSTALL_FAILED");
        when(workers.inspect(any(), any(), any(), any(), any())).thenReturn(new WorkflowPluginWorkerClient.WorkerPackage(
            "N8N", "n8n-nodes-example", "1.2.3", fingerprint, "node", List.of(failed)));

        service.snapshot("N8N", entry, true);
        service.dispatch();
        for (int attempt = 0; attempt < 100; attempt++) {
            Integer count = jdbc.queryForObject(
                "SELECT attempt_count FROM workflow_marketplace_plugin_probe", Integer.class);
            String status = jdbc.queryForObject(
                "SELECT probe_status FROM workflow_marketplace_plugin_probe", String.class);
            if (count == 1 && "QUEUED".equals(status)) break;
            Thread.sleep(20);
        }
        assertEquals("QUEUED", jdbc.queryForObject(
            "SELECT probe_status FROM workflow_marketplace_plugin_probe", String.class));
        assertEquals("UNSUPPORTED", jdbc.queryForObject(
            "SELECT compatibility_status FROM workflow_marketplace_plugin_probe", String.class));
    }

    /** 旧缓存中的依赖故障在再次访问时应自动恢复为待探测。 */
    @Test
    void requeuesCachedDependencyFailureOnPageVisit() throws Exception {
        var entry = n8nEntry();
        WorkflowPluginWorkerClient.WorkerComponent failed = new WorkflowPluginWorkerClient.WorkerComponent(
            "action", "Action", "", "ACTION", mapper.createArrayNode(), mapper.createArrayNode(), "node.js",
            "PARTIAL", "DEPENDENCY_INSTALL_TIMEOUT");
        String result = mapper.writeValueAsString(new WorkflowPluginWorkerClient.WorkerPackage("N8N",
            "n8n-nodes-example", "1.2.3", "8".repeat(64), "node", List.of(failed)));
        jdbc.update("""
            INSERT INTO workflow_marketplace_plugin_probe(source,catalog_external_key,package_key,package_version,
                package_fingerprint,probe_status,compatibility_status,result_json,next_attempt_at,last_accessed_at)
            VALUES ('N8N',?,?,? ,?,'COMPLETE','UNSUPPORTED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, entry.externalId(), "n8n-nodes-example", entry.version(), "8".repeat(64), result);

        WorkflowPluginProbeService.ProbeSnapshot snapshot = service.snapshot("N8N", entry, true);

        assertEquals("QUEUED", snapshot.probeStatus());
        assertEquals(0, jdbc.queryForObject(
            "SELECT attempt_count FROM workflow_marketplace_plugin_probe", Integer.class));
    }

    /** Worker 曾短暂不可用并耗尽重试后，适配器恢复时访问市场应重新排队。 */
    @ParameterizedTest
    @ValueSource(strings = {"workflow.pluginWorkerUnavailable", "workflow.adapterDisabled"})
    void requeuesCachedWorkerUnavailableFailureOnPageVisit(String reason) {
        var entry = difyEntry();
        jdbc.update("""
            INSERT INTO workflow_marketplace_plugin_probe(source,catalog_external_key,package_key,package_version,
                probe_status,compatibility_status,compatibility_reason,attempt_count,next_attempt_at,last_accessed_at)
            VALUES ('DIFY',?,?,?,'FAILED','UNSUPPORTED',?,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, entry.externalId(), entry.externalId(), entry.version(), reason);

        WorkflowPluginProbeService.ProbeSnapshot retry = service.snapshot("DIFY", entry, true);

        assertEquals("QUEUED", retry.probeStatus());
        assertEquals(0, jdbc.queryForObject(
            "SELECT attempt_count FROM workflow_marketplace_plugin_probe", Integer.class));
    }

    /** 旧 Worker ABI 结果必须在再次访问市场时失效，避免历史误判永久保留。 */
    @Test
    void requeuesCachedResultFromPreviousWorkerAbi() throws Exception {
        var entry = n8nEntry();
        WorkflowPluginWorkerClient.WorkerComponent failed = new WorkflowPluginWorkerClient.WorkerComponent(
            "action", "Action", "", "ACTION", mapper.createArrayNode(), mapper.createArrayNode(), "node.js",
            "PARTIAL", "DECLARATIVE_ROUTING_NOT_IMPLEMENTED");
        String result = mapper.writeValueAsString(new WorkflowPluginWorkerClient.WorkerPackage("N8N",
            "n8n-nodes-example", "1.2.3", "7".repeat(64), "node", 1, List.of(failed)));
        jdbc.update("""
            INSERT INTO workflow_marketplace_plugin_probe(source,catalog_external_key,package_key,package_version,
                package_fingerprint,probe_status,compatibility_status,compatibility_reason,result_json,
                next_attempt_at,last_accessed_at)
            VALUES ('N8N',?,?,? ,?,'COMPLETE','UNSUPPORTED','ROUTING_UNSUPPORTED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, entry.externalId(), "n8n-nodes-example", entry.version(), "7".repeat(64), result);

        WorkflowPluginProbeService.ProbeSnapshot snapshot = service.snapshot("N8N", entry, true);

        assertEquals("QUEUED", snapshot.probeStatus());
        assertEquals(0, jdbc.queryForObject(
            "SELECT attempt_count FROM workflow_marketplace_plugin_probe", Integer.class));
    }

    /** 旧文件数策略产生的拒绝只允许在新策略下额外重试一次。 */
    @ParameterizedTest
    @ValueSource(strings = {"PACKAGE_CONTENT_LIMIT", "workflow.pluginWorkerRejected"})
    void retriesLegacyContentLimitRejectionOnce(String reason) {
        var entry = difyEntry();
        jdbc.update("""
            INSERT INTO workflow_marketplace_plugin_probe(source,catalog_external_key,package_key,package_version,
                probe_status,compatibility_status,compatibility_reason,attempt_count,next_attempt_at,last_accessed_at)
            VALUES ('DIFY',?,?,?,'REJECTED','UNSUPPORTED',?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, entry.externalId(), entry.externalId(), entry.version(), reason);

        WorkflowPluginProbeService.ProbeSnapshot retry = service.snapshot("DIFY", entry, true);

        assertEquals("QUEUED", retry.probeStatus());
        assertEquals(1, jdbc.queryForObject(
            "SELECT attempt_count FROM workflow_marketplace_plugin_probe", Integer.class));

        jdbc.update("""
            UPDATE workflow_marketplace_plugin_probe SET probe_status='REJECTED',compatibility_status='UNSUPPORTED',
                compatibility_reason=?,attempt_count=2
            """, reason);
        assertEquals("REJECTED", service.snapshot("DIFY", entry, true).probeStatus());
    }

    /** 过期未安装缓存可清理，已安装指纹必须保留。 */
    @Test
    void cleansOnlyExpiredUninstalledPackage() {
        String removable = "e".repeat(64);
        insertExpired(removable);
        service.cleanupExpired();
        verify(workers).remove("N8N", removable);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_marketplace_plugin_probe", Integer.class));

        String installed = "f".repeat(64);
        insertExpired(installed);
        jdbc.update("INSERT INTO workflow_marketplace_plugin(id,package_fingerprint) VALUES (?,?)", 1L, installed);
        service.cleanupExpired();
        verify(workers, never()).remove("N8N", installed);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_marketplace_plugin_probe", Integer.class));
    }

    /** 等待后台线程把指定状态写入数据库。 */
    private void awaitStatus(String expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String actual = jdbc.queryForObject(
                "SELECT probe_status FROM workflow_marketplace_plugin_probe", String.class);
            if (expected.equals(actual)) return;
            Thread.sleep(20);
        }
        assertEquals(expected, jdbc.queryForObject(
            "SELECT probe_status FROM workflow_marketplace_plugin_probe", String.class));
    }

    /** 构造稳定 n8n 市场包身份。 */
    private WorkflowMarketplaceClients.MarketplaceEntry n8nEntry() {
        return n8nEntry("n8n-nodes-example.action", "n8n-nodes-example");
    }

    /** 构造可区分节点身份与包身份的 n8n 市场条目。 */
    private WorkflowMarketplaceClients.MarketplaceEntry n8nEntry(String externalId, String packageName) {
        return new WorkflowMarketplaceClients.MarketplaceEntry(externalId, "Example", "",
            "1.2.3", "vendor", "community-node", "community",
            mapper.valueToTree(Map.of("packageName", packageName)));
    }

    /** 构造固定版本 Dify 市场包。 */
    private WorkflowMarketplaceClients.MarketplaceEntry difyEntry() {
        return new WorkflowMarketplaceClients.MarketplaceEntry("langgenius/example", "Example", "",
            "1.2.3", "langgenius", "tool", "verified", mapper.createObjectNode());
    }

    /** 构造一个不含凭据的规范 Worker 探测结果。 */
    private WorkflowPluginWorkerClient.WorkerPackage workerPackage(String fingerprint, String status) {
        return workerPackage(fingerprint, List.of(status));
    }

    /** 构造具有多个兼容状态的规范 Worker 探测结果。 */
    private WorkflowPluginWorkerClient.WorkerPackage workerPackage(String fingerprint, List<String> statuses) {
        return new WorkflowPluginWorkerClient.WorkerPackage("N8N", "n8n-nodes-example", "1.2.3", fingerprint,
            "node", java.util.stream.IntStream.range(0, statuses.size()).mapToObj(index ->
                new WorkflowPluginWorkerClient.WorkerComponent("action" + index, "Action", "", "ACTION",
                    mapper.createArrayNode(), mapper.createArrayNode(), "node.js", statuses.get(index), ""))
                .toList());
    }

    /** 插入一个超过默认七天保留期的完整探测包。 */
    private void insertExpired(String fingerprint) {
        jdbc.update("""
            INSERT INTO workflow_marketplace_plugin_probe(source,catalog_external_key,package_key,package_version,
                package_fingerprint,probe_status,compatibility_status,result_json,next_attempt_at,last_accessed_at)
            VALUES ('N8N','node',?, '1',?,'COMPLETE','SUPPORTED','{}',CURRENT_TIMESTAMP,?)
            """, "pkg-" + fingerprint.substring(0, 4), fingerprint, java.sql.Timestamp.valueOf("2000-01-01 00:00:00"));
    }

    /** 提供包级兼容状态分支。 */
    private static Stream<Arguments> compatibilityCases() {
        return Stream.of(Arguments.of(List.of("SUPPORTED"), "SUPPORTED"),
            Arguments.of(List.of("SUPPORTED", "PARTIAL"), "PARTIAL"),
            Arguments.of(List.of("PARTIAL", "UNSUPPORTED"), "UNSUPPORTED"));
    }
}
