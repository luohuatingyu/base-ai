package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 管理 Webhook、Cron 和消息队列事件的幂等工作流启动。 */
@Service
public class WorkflowTriggerService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerService.class);
    private final WorkflowService workflowService;
    private final WorkflowExecutionService executionService;
    private final WorkflowConnectionService connectionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final int retentionDays;

    /** 注入工作流、连接、执行和持久化服务。 */
    public WorkflowTriggerService(WorkflowService workflowService, WorkflowExecutionService executionService,
                                  WorkflowConnectionService connectionService,
                                  @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper, PlatformProperties properties) {
        this.workflowService = workflowService;
        this.executionService = executionService;
        this.connectionService = connectionService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.retentionDays = Math.max(1, properties.getWorkflow().getTriggerDeliveryRetentionDays());
    }

    /** 每三十秒把已发布 Cron 触发器同步到持久调度表。 */
    @Scheduled(fixedDelay = 30_000L)
    @Transactional
    public void refreshSchedules() {
        jdbcTemplate.update("UPDATE workflow_schedule_state SET active=false,updated_at=NOW() WHERE active=true");
        workflowService.triggerDefinitions().stream().filter(item -> "SCHEDULE_TRIGGER".equals(item.nodeType()))
            .filter(this::configurationExecutable).forEach(this::upsertSchedule);
    }

    /** 跳过资源修订失效或配置不完整的触发版本，等待管理员重新发布。 */
    private boolean configurationExecutable(WorkflowModels.TriggerDefinition trigger) {
        try { workflowService.validateExecutableConfiguration(workflowService.storedVersion(trigger.versionId())); return true; }
        catch (RuntimeException exception) { log.warn("Workflow trigger disabled until republished: {}", key(trigger)); return false; }
    }

    /** 接收并验证公开 Webhook 请求。 */
    @Transactional
    public WorkflowModels.RunAccepted webhook(String workflowCode, String nodeId, String signature,
                                               String eventId, byte[] rawBody) {
        WorkflowModels.TriggerDefinition trigger = requireTrigger(workflowCode, nodeId, "WEBHOOK_TRIGGER");
        WorkflowConnectionService.StoredConnection connection = connectionService.resolved(
            trigger.config().path("connectionId").asLong(), java.util.Set.of("WEBHOOK"));
        String secret = connection.config().path("secret").asText();
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new BusinessException(503, "workflow.webhookUnavailable");
        if (!validSignature(secret, rawBody, signature)) throw new BusinessException(401, "workflow.webhookSignatureInvalid");
        String body = new String(rawBody == null ? new byte[0] : rawBody, StandardCharsets.UTF_8);
        // 调用方未提供事件 ID 时按至少一次语义处理，每次请求生成独立标识，避免相同正文被错误去重。
        String stableEventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : validateEventId(eventId);
        Map<String, Object> inputs = bodyInputs(body);
        return startUnique(trigger, stableEventId, inputs, "WEBHOOK");
    }

    /** 兼容内部单元测试的文本入口，生产控制器始终传递原始字节。 */
    WorkflowModels.RunAccepted webhook(String workflowCode, String nodeId, String signature, String eventId, String body) {
        return webhook(workflowCode, nodeId, signature, eventId,
            (body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
    }

    /** 接收消息消费者转换后的事件并执行数据库幂等检查。 */
    @Transactional
    public WorkflowModels.RunAccepted message(WorkflowModels.TriggerDefinition trigger, String eventId,
                                               Map<String, Object> inputs) {
        String stableEventId = eventId != null && eventId.matches("[A-Za-z0-9._:-]{1,160}") ? eventId
            : sha256((eventId == null ? "" : eventId).getBytes(StandardCharsets.UTF_8));
        return startUnique(trigger, stableEventId, inputs, trigger.nodeType());
    }

    /** 查找指定已发布触发节点。 */
    public WorkflowModels.TriggerDefinition requireTrigger(String workflowCode, String nodeId, String type) {
        return workflowService.triggerDefinitions().stream()
            .filter(item -> item.workflowCode().equalsIgnoreCase(workflowCode) && item.nodeId().equals(nodeId)
                && item.nodeType().equals(type)).findFirst()
            .orElseThrow(() -> BusinessException.notFound("workflow.triggerNotFound"));
    }

    /** 原子记录事件 ID，只有首次事件可以创建工作流运行。 */
    private WorkflowModels.RunAccepted startUnique(WorkflowModels.TriggerDefinition trigger, String eventId,
                                                   Map<String, Object> inputs, String triggerType) {
        try {
            jdbcTemplate.update("""
                INSERT INTO workflow_trigger_delivery(workflow_id,trigger_node_id,event_id) VALUES (?,?,?)
                """, trigger.workflowId(), trigger.nodeId(), eventId);
        } catch (DuplicateKeyException exception) { throw new BusinessException(409, "workflow.triggerDuplicate"); }
        try {
            WorkflowModels.RunAccepted accepted = executionService.startTriggered(trigger.workflowCode(), inputs, triggerType);
            int linked = jdbcTemplate.update("""
                UPDATE workflow_trigger_delivery SET run_id=?, delivery_status='ENQUEUED', last_error='' WHERE workflow_id=? AND trigger_node_id=? AND event_id=?
                """, accepted.runId(), trigger.workflowId(), trigger.nodeId(), eventId);
            if (linked != 1) throw new BusinessException("workflow.triggerDeliveryLinkFailed");
            return accepted;
        } catch (RuntimeException exception) {
            jdbcTemplate.update("DELETE FROM workflow_trigger_delivery WHERE workflow_id=? AND trigger_node_id=? AND event_id=? AND run_id IS NULL",
                trigger.workflowId(), trigger.nodeId(), eventId);
            throw exception;
        }
    }

    /** 校验并保存单个 Cron 的稳定下一次计划时间。 */
    private void upsertSchedule(WorkflowModels.TriggerDefinition trigger) {
        String expression = trigger.config().path("cron").asText();
        if (expression.isBlank()) throw new BusinessException("workflow.scheduleInvalid");
        ZoneId zone;
        try { zone = ZoneId.of(trigger.config().path("zoneId").asText("Asia/Shanghai")); }
        catch (Exception exception) { throw new BusinessException("workflow.scheduleInvalid"); }
        Instant next = nextFire(expression, zone, Instant.now());
        jdbcTemplate.update("""
            INSERT INTO workflow_schedule_state(workflow_id,workflow_version_id,trigger_node_id,cron_expression,zone_id,next_fire_at,active)
            VALUES (?,?,?,?,?,?,true)
            ON DUPLICATE KEY UPDATE
                next_fire_at=IF(workflow_version_id<>VALUES(workflow_version_id) OR cron_expression<>VALUES(cron_expression)
                    OR zone_id<>VALUES(zone_id),VALUES(next_fire_at),next_fire_at),
                workflow_version_id=VALUES(workflow_version_id),cron_expression=VALUES(cron_expression),
                zone_id=VALUES(zone_id),active=true,updated_at=NOW()
            """, trigger.workflowId(), trigger.versionId(), trigger.nodeId(), expression, zone.getId(),
            java.sql.Timestamp.from(next));
    }

    /** 每秒原子推进一个到期计划；停机后只补跑最近一次并跳到当前时间之后。 */
    @Scheduled(fixedDelay = 1_000L)
    @Transactional
    public void fireDueSchedule() {
        List<ScheduleRow> due = jdbcTemplate.query("""
            SELECT workflow_id,workflow_version_id,trigger_node_id,cron_expression,zone_id,next_fire_at
            FROM workflow_schedule_state WHERE active=true AND next_fire_at<=NOW()
            ORDER BY next_fire_at LIMIT 1
            """, (rs, row) -> new ScheduleRow(rs.getLong("workflow_id"), rs.getLong("workflow_version_id"),
            rs.getString("trigger_node_id"), rs.getString("cron_expression"), rs.getString("zone_id"),
            rs.getTimestamp("next_fire_at").toInstant()));
        if (due.isEmpty()) return;
        ScheduleRow row = due.get(0);
        WorkflowModels.TriggerDefinition trigger = workflowService.triggerDefinitions().stream()
            .filter(item -> item.workflowId().equals(row.workflowId()) && item.versionId().equals(row.versionId())
                && item.nodeId().equals(row.nodeId()) && "SCHEDULE_TRIGGER".equals(item.nodeType()))
            .findFirst().orElse(null);
        if (trigger == null || !configurationExecutable(trigger)) {
            jdbcTemplate.update("UPDATE workflow_schedule_state SET active=false,updated_at=NOW() WHERE workflow_id=? AND trigger_node_id=?",
                row.workflowId(), row.nodeId());
            return;
        }
        Instant now = Instant.now();
        ZoneId zone = ZoneId.of(row.zoneId());
        Instant latest = latestDueFire(row.fireAt(), row.expression(), zone, now);
        Instant next = nextFire(row.expression(), zone, now);
        int claimed = jdbcTemplate.update("""
            UPDATE workflow_schedule_state SET next_fire_at=?,last_fire_at=?,updated_at=NOW()
            WHERE workflow_id=? AND trigger_node_id=? AND active=true AND next_fire_at=?
            """, java.sql.Timestamp.from(next), java.sql.Timestamp.from(latest), row.workflowId(), row.nodeId(),
            java.sql.Timestamp.from(row.fireAt()));
        if (claimed != 1) return;
        String eventId = latest.truncatedTo(ChronoUnit.SECONDS).toString();
        startUnique(trigger, eventId, Map.of("triggeredAt", eventId), "SCHEDULE");
    }

    /** 从最早未触发时间推进到当前，保留唯一一条最近的停机补偿事件。 */
    Instant latestDueFire(Instant firstDue, String expression, ZoneId zone, Instant now) {
        Instant latest = firstDue;
        while (true) {
            Instant candidate = nextFire(expression, zone, latest);
            if (candidate.isAfter(now)) return latest;
            latest = candidate;
        }
    }

    /** 计算严格晚于给定时刻的下一次 Cron 时间。 */
    private Instant nextFire(String expression, ZoneId zone, Instant after) {
        try {
            java.time.ZonedDateTime next = CronExpression.parse(expression)
                .next(java.time.ZonedDateTime.ofInstant(after, zone));
            if (next == null) throw new BusinessException("workflow.scheduleInvalid");
            return next.toInstant();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.scheduleInvalid"); }
    }

    /** 使用 HMAC-SHA256 常量时间校验 Webhook 签名。 */
    private boolean validSignature(String secret, byte[] body, String provided) {
        if (provided == null || provided.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(body == null ? new byte[0] : body));
            String normalized = provided.startsWith("sha256=") ? provided.substring(7) : provided;
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), normalized.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) { throw new IllegalStateException("Webhook 签名算法不可用", exception); }
    }

    /** 将 JSON 对象正文转换为工作流输入，其他正文保留为 body 字段。 */
    private Map<String, Object> bodyInputs(String body) {
        try {
            JsonNode value = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (value.isObject()) return objectMapper.convertValue(value, new TypeReference<>() { });
            return Map.of("body", objectMapper.convertValue(value, Object.class));
        } catch (Exception exception) { return Map.of("body", body == null ? "" : body); }
    }

    /** 计算无调用方事件 ID 时使用的稳定正文摘要。 */
    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value == null ? new byte[0] : value)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }

    /** 拒绝超长或含控制字符的事件标识，避免数据库截断碰撞。 */
    private String validateEventId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9._:-]{1,160}")) throw new BusinessException("workflow.triggerEventIdInvalid");
        return normalized;
    }

    /** 生成包含版本的调度任务键，发布新版本后自动替换。 */
    private String key(WorkflowModels.TriggerDefinition trigger) {
        return trigger.workflowId() + ":" + trigger.versionId() + ":" + trigger.nodeId();
    }

    /** 每日清理超过保留期的触发幂等记录，避免表无限增长。 */
    @Scheduled(cron = "0 20 3 * * *")
    public void cleanupDeliveries() {
        jdbcTemplate.update("DELETE FROM workflow_trigger_delivery WHERE received_at<?",
            java.sql.Timestamp.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS)));
    }

    /** 回收创建运行后尚未关联 run_id 的投递记录，允许崩溃后的事件安全重试。 */
    @Scheduled(fixedDelay = 30_000L)
    public void recoverOrphanDeliveries() {
        jdbcTemplate.update("""
            DELETE FROM workflow_trigger_delivery
            WHERE run_id IS NULL AND received_at<?
            """, java.sql.Timestamp.from(Instant.now().minus(2, ChronoUnit.MINUTES)));
    }

    /** 兼容既有生命周期测试；持久调度器不再持有本地线程。 */
    void shutdown() { }

    private record ScheduleRow(Long workflowId, Long versionId, String nodeId, String expression,
                               String zoneId, Instant fireAt) {}
}
