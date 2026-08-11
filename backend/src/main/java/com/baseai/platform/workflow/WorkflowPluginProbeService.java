package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** 持久化市场包探测队列，并在隔离 Worker 中异步完成安全与 ABI 探测。 */
@Service
public class WorkflowPluginProbeService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowPluginProbeService.class);
    private static final Duration LEASE = Duration.ofMinutes(10);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowMarketplaceClients clients;
    private final WorkflowPluginWorkerClient workers;
    private final ThreadPoolTaskExecutor executor;
    private final int maximumAttempts;
    private final int retentionHours;
    private final String instanceId;

    /** 注入市场、Worker、持久化入口和独立有界线程池。 */
    public WorkflowPluginProbeService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                      ObjectMapper objectMapper, WorkflowMarketplaceClients clients,
                                      WorkflowPluginWorkerClient workers,
                                      @Qualifier("workflowPluginProbeExecutor") ThreadPoolTaskExecutor executor,
                                      PlatformProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clients = clients;
        this.workers = workers;
        this.executor = executor;
        maximumAttempts = Math.max(1, Math.min(properties.getWorkflow().getMarketplaceProbeMaxAttempts(), 10));
        retentionHours = Math.max(1, Math.min(properties.getWorkflow().getMarketplaceProbeRetentionHours(), 24 * 365));
        instanceId = properties.getPythonWorker().getJavaInstanceId() + "-plugin-probe";
    }

    /** 返回固定市场版本的当前探测快照，并按权限决定是否幂等入队。 */
    public ProbeSnapshot snapshot(String rawSource, WorkflowMarketplaceClients.MarketplaceEntry entry,
                                  boolean enqueue) {
        String source = source(rawSource);
        PackageIdentity identity = identity(source, entry);
        if (enqueue) enqueue(identity);
        List<ProbeRow> rows = jdbcTemplate.query("""
            SELECT id,probe_status,compatibility_status,compatibility_reason,result_json
            FROM workflow_marketplace_plugin_probe WHERE source=? AND package_key=? AND package_version=?
            """, (rs, row) -> new ProbeRow(rs.getLong("id"), rs.getString("probe_status"),
            rs.getString("compatibility_status"), rs.getString("compatibility_reason"),
            rs.getString("result_json")), source, identity.packageKey(), identity.version());
        if (rows.isEmpty()) return new ProbeSnapshot("NOT_PROBED", "PROBING", "", null);
        ProbeRow row = rows.get(0);
        jdbcTemplate.update("UPDATE workflow_marketplace_plugin_probe SET last_accessed_at=?,updated_at=? WHERE id=?",
            timestamp(Instant.now()), timestamp(Instant.now()), row.id());
        WorkflowPluginWorkerClient.WorkerPackage inspected = row.resultJson() == null ? null : parse(row.resultJson());
        if (enqueue && "COMPLETE".equals(row.probeStatus()) && inspected != null
            && dependencyUnavailable(inspected)) {
            jdbcTemplate.update("""
                UPDATE workflow_marketplace_plugin_probe SET probe_status='QUEUED',compatibility_status='PROBING',
                    compatibility_reason='',result_json=NULL,package_fingerprint=NULL,attempt_count=0,next_attempt_at=?,
                    lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=? AND probe_status='COMPLETE'
                """, timestamp(Instant.now()), timestamp(Instant.now()), row.id());
            return new ProbeSnapshot("QUEUED", "PROBING", "", null);
        }
        return new ProbeSnapshot(row.probeStatus(), row.compatibilityStatus(), row.compatibilityReason(), inspected);
    }

    /** 导入只能读取已完成的固定版本探测结果，不允许退化为同步下载或探测。 */
    public WorkflowPluginWorkerClient.WorkerPackage requireCompleted(
        String rawSource, WorkflowMarketplaceClients.MarketplaceEntry entry) {
        ProbeSnapshot snapshot = snapshot(rawSource, entry, false);
        if (!"COMPLETE".equals(snapshot.probeStatus()) || snapshot.inspected() == null) {
            throw new BusinessException(409, "workflow.marketplaceProbeIncomplete");
        }
        return snapshot.inspected();
    }

    /** 领取排队任务并提交到独立线程池；租约确保多实例不会重复执行。 */
    @Scheduled(fixedDelayString = "${WORKFLOW_MARKETPLACE_PROBE_DISPATCH_DELAY_MS:1000}")
    public void dispatch() {
        Instant now = Instant.now();
        recoverExpired(now);
        int available = Math.max(0, executor.getMaxPoolSize() - executor.getActiveCount());
        if (available == 0) return;
        List<ProbeTask> tasks = jdbcTemplate.query("""
            SELECT id,source,catalog_external_key,package_key,package_version,attempt_count,created_at
            FROM workflow_marketplace_plugin_probe
            WHERE probe_status='QUEUED' AND next_attempt_at<=? ORDER BY id LIMIT ?
            """, (rs, row) -> new ProbeTask(rs.getLong("id"), rs.getString("source"),
            rs.getString("catalog_external_key"), rs.getString("package_key"),
            rs.getString("package_version"), rs.getInt("attempt_count") + 1,
            rs.getTimestamp("created_at").toInstant()), timestamp(now), available);
        for (ProbeTask task : tasks) submit(task, now);
    }

    /** 清理未安装且超过保留期的包缓存，已安装指纹永远不由探测缓存删除。 */
    @Scheduled(cron = "0 40 3 * * *")
    public void cleanupExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(retentionHours));
        List<ExpiredProbe> expired = jdbcTemplate.query("""
            SELECT q.id,q.source,q.package_fingerprint FROM workflow_marketplace_plugin_probe q
            WHERE q.last_accessed_at<? AND q.probe_status IN ('COMPLETE','FAILED','REJECTED')
              AND (q.package_fingerprint IS NULL OR NOT EXISTS (
                  SELECT 1 FROM workflow_marketplace_plugin p
                  WHERE p.package_fingerprint=q.package_fingerprint))
            ORDER BY q.id LIMIT 100
            """, (rs, row) -> new ExpiredProbe(rs.getLong("id"), rs.getString("source"),
            rs.getString("package_fingerprint")), timestamp(cutoff));
        for (ExpiredProbe probe : expired) {
            try {
                int claimed = jdbcTemplate.update("""
                    UPDATE workflow_marketplace_plugin_probe SET probe_status='CLEANING',updated_at=?
                    WHERE id=? AND last_accessed_at<? AND probe_status IN ('COMPLETE','FAILED','REJECTED')
                    """, timestamp(Instant.now()), probe.id(), timestamp(cutoff));
                if (claimed != 1) continue;
                if (probe.fingerprint() != null) workers.remove(probe.source(), probe.fingerprint());
                jdbcTemplate.update("DELETE FROM workflow_marketplace_plugin_probe WHERE id=? AND probe_status='CLEANING'",
                    probe.id());
            } catch (BusinessException exception) {
                jdbcTemplate.update("""
                    UPDATE workflow_marketplace_plugin_probe SET probe_status=CASE
                        WHEN result_json IS NULL THEN 'FAILED' ELSE 'COMPLETE' END,updated_at=?
                    WHERE id=? AND probe_status='CLEANING'
                    """, timestamp(Instant.now()), probe.id());
                log.warn("plugin probe cache cleanup deferred id={} reason={}", probe.id(), exception.getMessageKey());
            }
        }
    }

    /** 计算市场条目对应的稳定包身份，n8n 节点身份与 npm 包身份保持分离。 */
    public PackageIdentity identity(String rawSource, WorkflowMarketplaceClients.MarketplaceEntry entry) {
        String source = source(rawSource);
        String packageKey = "N8N".equals(source) ? entry.raw().path("packageName").asText("").trim()
            : entry.externalId();
        if (packageKey.isBlank() || entry.externalId() == null || entry.externalId().isBlank()
            || entry.version() == null || entry.version().isBlank()) {
            throw new BusinessException("workflow.marketplacePackageInvalid");
        }
        return new PackageIdentity(source, text(entry.externalId(), 255), text(packageKey, 255),
            text(entry.version(), 64));
    }

    /** 幂等写入新任务，已有固定版本只刷新访问时间。 */
    private void enqueue(PackageIdentity identity) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
            UPDATE workflow_marketplace_plugin_probe SET catalog_external_key=?,last_accessed_at=?,updated_at=?
            WHERE source=? AND package_key=? AND package_version=?
            """, identity.catalogExternalKey(), timestamp(now), timestamp(now), identity.source(),
            identity.packageKey(), identity.version());
        if (updated != 0) return;
        try {
            jdbcTemplate.update("""
                INSERT INTO workflow_marketplace_plugin_probe(source,catalog_external_key,package_key,package_version,
                    probe_status,compatibility_status,next_attempt_at,last_accessed_at)
                VALUES (?,?,?,?,'QUEUED','PROBING',?,?)
                """, identity.source(), identity.catalogExternalKey(), identity.packageKey(), identity.version(),
                timestamp(now), timestamp(now));
        } catch (DuplicateKeyException ignored) {
            jdbcTemplate.update("""
                UPDATE workflow_marketplace_plugin_probe SET last_accessed_at=?,updated_at=?
                WHERE source=? AND package_key=? AND package_version=?
                """, timestamp(now), timestamp(now), identity.source(), identity.packageKey(), identity.version());
        }
    }

    /** 原子领取单个任务，并在队列拒绝时恢复为可重试状态。 */
    private void submit(ProbeTask task, Instant now) {
        int claimed = jdbcTemplate.update("""
            UPDATE workflow_marketplace_plugin_probe SET probe_status='PROBING',attempt_count=?,lease_owner=?,
                lease_expires_at=?,updated_at=? WHERE id=? AND probe_status='QUEUED'
            """, task.attempt(), instanceId, timestamp(now.plus(LEASE)), timestamp(now), task.id());
        if (claimed != 1) return;
        try {
            executor.execute(() -> probe(task));
        } catch (TaskRejectedException exception) {
            jdbcTemplate.update("""
                UPDATE workflow_marketplace_plugin_probe SET probe_status='QUEUED',lease_owner=NULL,
                    lease_expires_at=NULL,updated_at=? WHERE id=? AND lease_owner=?
                """, timestamp(Instant.now()), task.id(), instanceId);
        }
    }

    /** 下载、校验并探测一个固定版本插件包，将完整结果一次性写回。 */
    private void probe(ProbeTask task) {
        long started = System.nanoTime();
        long downloadStarted = started;
        try {
            byte[] archive;
            String fingerprint;
            if ("N8N".equals(task.source())) {
                WorkflowMarketplaceClients.MarketplaceEntry entry = clients.findN8n(task.catalogExternalKey())
                    .orElseThrow(() -> new BusinessException("workflow.marketplaceNodeNotFound"));
                PackageIdentity current = identity(task.source(), entry);
                if (!current.packageKey().equals(task.packageKey()) || !current.version().equals(task.version())) {
                    throw new BusinessException("workflow.marketplaceProbeVersionChanged");
                }
                WorkflowMarketplaceClients.PackageDownload download = clients.downloadN8nPackage(entry);
                archive = download.bytes();
                fingerprint = download.fingerprint();
            } else {
                archive = clients.downloadDifyPackage(task.packageKey(), task.version());
                fingerprint = WorkflowNodeMarketplaceService.sha256Bytes(archive);
            }
            long downloaded = System.nanoTime();
            WorkflowPluginWorkerClient.WorkerPackage inspected = workers.inspect(task.source(), task.packageKey(),
                task.version(), archive, fingerprint);
            long inspectedAt = System.nanoTime();
            if (dependencyUnavailable(inspected)) {
                throw new BusinessException("workflow.pluginWorkerUnavailable");
            }
            String compatibility = compatibility(inspected);
            jdbcTemplate.update("""
                UPDATE workflow_marketplace_plugin_probe SET package_fingerprint=?,probe_status='COMPLETE',
                    compatibility_status=?,compatibility_reason=?,result_json=?,lease_owner=NULL,lease_expires_at=NULL,
                    probed_at=?,last_accessed_at=?,updated_at=? WHERE id=? AND lease_owner=?
                """, inspected.fingerprint(), compatibility,
                "UNSUPPORTED".equals(compatibility) ? "NO_EXECUTABLE_COMPONENT" : "", json(inspected),
                timestamp(Instant.now()), timestamp(Instant.now()), timestamp(Instant.now()), task.id(), instanceId);
            log.info("plugin probe completed id={} source={} attempt={} queueMs={} downloadMs={} workerMs={} totalMs={}",
                task.id(), task.source(), task.attempt(),
                Math.max(0, Duration.between(task.createdAt(), Instant.now()).toMillis()
                    - Duration.ofNanos(inspectedAt - started).toMillis()),
                Duration.ofNanos(downloaded - downloadStarted).toMillis(),
                Duration.ofNanos(inspectedAt - downloaded).toMillis(),
                Duration.ofNanos(inspectedAt - started).toMillis());
        } catch (BusinessException exception) {
            log.warn("plugin probe failed id={} source={} attempt={} elapsedMs={} reason={}", task.id(), task.source(),
                task.attempt(), Duration.ofNanos(System.nanoTime() - started).toMillis(), exception.getMessageKey());
            fail(task, exception.getMessageKey(), terminal(exception));
        } catch (RuntimeException exception) {
            log.warn("plugin probe failed id={} source={} attempt={} elapsedMs={} reason=unexpected", task.id(),
                task.source(), task.attempt(), Duration.ofNanos(System.nanoTime() - started).toMillis());
            fail(task, "workflow.pluginWorkerUnavailable", false);
        }
    }

    /** 按错误类型和次数决定终止、拒绝或延迟重试。 */
    private void fail(ProbeTask task, String reason, boolean rejected) {
        boolean exhausted = task.attempt() >= maximumAttempts;
        String status = rejected ? "REJECTED" : exhausted ? "FAILED" : "QUEUED";
        Instant next = Instant.now().plusSeconds(Math.min(60, 1L << Math.min(task.attempt(), 5)));
        jdbcTemplate.update("""
            UPDATE workflow_marketplace_plugin_probe SET probe_status=?,compatibility_status='UNSUPPORTED',
                compatibility_reason=?,next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL,updated_at=?
            WHERE id=? AND lease_owner=?
            """, status, text(reason, 500), timestamp(next), timestamp(Instant.now()), task.id(), instanceId);
    }

    /** 恢复过期租约，并把已耗尽次数的任务收敛为失败。 */
    private void recoverExpired(Instant now) {
        jdbcTemplate.update("""
            UPDATE workflow_marketplace_plugin_probe SET probe_status='FAILED',compatibility_status='UNSUPPORTED',
                compatibility_reason='workflow.marketplaceProbeAttemptsExhausted',lease_owner=NULL,
                lease_expires_at=NULL,updated_at=?
            WHERE probe_status='PROBING' AND lease_expires_at<? AND attempt_count>=?
            """, timestamp(now), timestamp(now), maximumAttempts);
        jdbcTemplate.update("""
            UPDATE workflow_marketplace_plugin_probe SET probe_status='QUEUED',lease_owner=NULL,
                lease_expires_at=NULL,next_attempt_at=?,updated_at=?
            WHERE probe_status='PROBING' AND lease_expires_at<? AND attempt_count<?
            """, timestamp(now), timestamp(now), timestamp(now), maximumAttempts);
    }

    /** 汇总组件兼容度；混合结果保留 PARTIAL，避免隐藏不支持组件。 */
    private String compatibility(WorkflowPluginWorkerClient.WorkerPackage inspected) {
        long supported = inspected.components().stream().filter(item -> "SUPPORTED".equals(item.compatibilityStatus())).count();
        if (supported == 0) return "UNSUPPORTED";
        return supported == inspected.components().size() ? "SUPPORTED" : "PARTIAL";
    }

    /** 依赖安装失败属于可恢复基础设施故障，不固化为永久 ABI 不兼容。 */
    private boolean dependencyUnavailable(WorkflowPluginWorkerClient.WorkerPackage inspected) {
        return inspected.components().stream().noneMatch(item -> "SUPPORTED".equals(item.compatibilityStatus()))
            && inspected.components().stream().allMatch(item -> List.of("DEPENDENCY_INSTALL_FAILED",
                "DEPENDENCY_INSTALL_TIMEOUT").contains(item.compatibilityReason()));
    }

    /** 安全拒绝类错误不做自动重试，网络和 Worker 可用性错误允许重试。 */
    private boolean terminal(BusinessException exception) {
        return List.of("workflow.marketplacePackageInvalid", "workflow.pluginWorkerRejected",
            "workflow.pluginWorkerResponseInvalid", "workflow.marketplaceNodeNotFound",
            "workflow.marketplaceProbeVersionChanged").contains(exception.getMessageKey());
    }

    /** 解析数据库中由本服务生成的 Worker 结果。 */
    private WorkflowPluginWorkerClient.WorkerPackage parse(String value) {
        try { return objectMapper.readValue(value, WorkflowPluginWorkerClient.WorkerPackage.class); }
        catch (Exception exception) { throw new IllegalStateException("插件探测结果无法解析", exception); }
    }

    /** 序列化不含凭据的规范化探测结果。 */
    private String json(WorkflowPluginWorkerClient.WorkerPackage value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("插件探测结果无法序列化", exception); }
    }

    /** 规范市场来源。 */
    private String source(String value) {
        String source = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("N8N", "DIFY").contains(source)) throw new BusinessException("workflow.marketplaceSourceInvalid");
        return source;
    }

    /** 截断公开市场标识和稳定原因码。 */
    private String text(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    /** 把 Java 时间转换为跨 MySQL/H2 可绑定的时间戳。 */
    private Timestamp timestamp(Instant value) { return Timestamp.from(value); }

    private record ProbeRow(long id, String probeStatus, String compatibilityStatus,
                            String compatibilityReason, String resultJson) {}
    private record ProbeTask(long id, String source, String catalogExternalKey, String packageKey,
                             String version, int attempt, Instant createdAt) {}
    private record ExpiredProbe(long id, String source, String fingerprint) {}
    public record PackageIdentity(String source, String catalogExternalKey, String packageKey, String version) {}
    public record ProbeSnapshot(String probeStatus, String compatibilityStatus, String compatibilityReason,
                                WorkflowPluginWorkerClient.WorkerPackage inspected) {}
}
