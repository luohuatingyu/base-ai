package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowTriggerServiceTest {
    private WorkflowService workflowService;
    private WorkflowExecutionService executionService;
    private WorkflowConnectionService connectionService;
    private JdbcTemplate jdbcTemplate;
    private WorkflowTriggerService service;

    /** 构造只包含一个 Webhook 的已发布工作流。 */
    @BeforeEach
    void setUp() throws Exception {
        workflowService = mock(WorkflowService.class);
        executionService = mock(WorkflowExecutionService.class);
        connectionService = mock(WorkflowConnectionService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        WorkflowModels.TriggerDefinition trigger = new WorkflowModels.TriggerDefinition(1L, "ORDERS", 2L, 7L,
            "hook", "WEBHOOK_TRIGGER", mapper.readTree("{\"connectionId\":9}"));
        when(workflowService.triggerDefinitions()).thenReturn(List.of(trigger));
        when(connectionService.resolved(9L, Set.of("WEBHOOK"))).thenReturn(new WorkflowConnectionService.StoredConnection(
            9L, "HOOK", "Hook", "WEBHOOK", mapper.readTree("{\"secret\":\"top-secret-0123456789abcdef0123456789\"}"), 7L, true, null, null));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(executionService.startTriggered(anyString(), any(Map.class), anyString()))
            .thenReturn(new WorkflowModels.RunAccepted("run-1", "QUEUED"));
        service = new WorkflowTriggerService(workflowService, executionService, connectionService, jdbcTemplate, mapper,
            new com.baseai.platform.config.PlatformProperties());
    }

    /** 停止测试调度器线程。 */
    @AfterEach
    void tearDown() { service.shutdown(); }

    /** 正确签名必须启动一次已发布工作流。 */
    @Test
    void acceptsSignedWebhook() throws Exception {
        String body = "{\"orderId\":42}";
        WorkflowModels.RunAccepted accepted = service.webhook("ORDERS", "hook",
            signature("top-secret-0123456789abcdef0123456789", body), "evt-1", body);
        assertEquals("run-1", accepted.runId());
        verify(executionService).startTriggered("ORDERS", Map.of("orderId", 42), "WEBHOOK");
    }

    /** 缺失或错误签名不得创建运行记录。 */
    @Test
    void rejectsInvalidSignature() {
        assertThrows(BusinessException.class, () -> service.webhook("ORDERS", "hook", "bad", "evt-2", "{}"));
    }

    /** 超长或含控制字符的事件 ID 必须拒绝，不能截断后产生幂等碰撞。 */
    @Test
    void rejectsInvalidEventIdWithoutTruncation() throws Exception {
        String body = "{}";
        String signature = signature("top-secret-0123456789abcdef0123456789", body);
        assertEquals("workflow.triggerEventIdInvalid", assertThrows(BusinessException.class,
            () -> service.webhook("ORDERS", "hook", signature, "x".repeat(161), body)).getMessageKey());
    }

    /** 弱 Webhook Secret 必须使公开入口安全失败，不能依赖调用方自觉配置强密钥。 */
    @Test
    void rejectsWeakWebhookSecret() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        when(connectionService.resolved(9L, Set.of("WEBHOOK"))).thenReturn(new WorkflowConnectionService.StoredConnection(
            9L, "HOOK", "Hook", "WEBHOOK", mapper.readTree("{\"secret\":\"short\"}"), 7L, true, null, null));
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.webhook("ORDERS", "hook", "invalid", "evt-3", "{}"));
        assertEquals(503, exception.getStatus());
    }

    /** 停机期间的多个 Cron 时间点只能补跑最后一个，避免恢复后突发执行历史积压。 */
    @Test
    void compensatesOnlyMostRecentMissedSchedule() {
        Instant firstDue = Instant.parse("2026-08-14T04:00:00Z");
        Instant now = Instant.parse("2026-08-14T04:05:30Z");

        Instant latest = service.latestDueFire(firstDue, "0 * * * * *", ZoneId.of("UTC"), now);

        assertEquals(Instant.parse("2026-08-14T04:05:00Z"), latest);
    }

    /** 生成测试 HMAC 签名。 */
    private String signature(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
