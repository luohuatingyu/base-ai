package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
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
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<String, ScheduledFuture<?>> schedules = new ConcurrentHashMap<>();
    private final int retentionDays;

    /** 注入工作流、连接、执行和持久化服务并启动内部 Cron 调度器。 */
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
        scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("workflow-trigger-"); scheduler.initialize();
    }

    /** 每三十秒同步一次已发布 Cron 触发器。 */
    @Scheduled(fixedDelay = 30_000L)
    public void refreshSchedules() {
        Map<String, WorkflowModels.TriggerDefinition> expected = new HashMap<>();
        workflowService.triggerDefinitions().stream().filter(item -> "SCHEDULE_TRIGGER".equals(item.nodeType()))
            .filter(this::configurationExecutable).forEach(item -> {
            String key = key(item); expected.put(key, item);
            schedules.computeIfAbsent(key, ignored -> schedule(item));
        });
        schedules.keySet().removeIf(key -> {
            if (expected.containsKey(key)) return false;
            ScheduledFuture<?> future = schedules.get(key); if (future != null) future.cancel(false); return true;
        });
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

    /** 注册单个 Cron 任务。 */
    private ScheduledFuture<?> schedule(WorkflowModels.TriggerDefinition trigger) {
        String expression = trigger.config().path("cron").asText();
        if (expression.isBlank()) throw new BusinessException("workflow.scheduleInvalid");
        ZoneId zone;
        try { zone = ZoneId.of(trigger.config().path("zoneId").asText("Asia/Shanghai")); }
        catch (Exception exception) { throw new BusinessException("workflow.scheduleInvalid"); }
        return scheduler.schedule(() -> {
            Instant firedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            try {
                startUnique(trigger, firedAt.toString(), Map.of("triggeredAt", firedAt.toString()), "SCHEDULE");
            } catch (BusinessException exception) {
                if (!"workflow.triggerDuplicate".equals(exception.getMessageKey())) log.error("Workflow schedule failed: {}", key(trigger));
            } catch (RuntimeException exception) { log.error("Workflow schedule failed: {}", key(trigger), exception); }
        }, new CronTrigger(expression, zone));
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

    /** 停止内部调度线程。 */
    @PreDestroy
    public void shutdown() { schedules.values().forEach(future -> future.cancel(false)); scheduler.shutdown(); }
}
