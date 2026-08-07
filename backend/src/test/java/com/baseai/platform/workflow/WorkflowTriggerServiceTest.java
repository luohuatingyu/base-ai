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
    private JdbcTemplate jdbcTemplate;
    private WorkflowTriggerService service;

    /** 构造只包含一个 Webhook 的已发布工作流。 */
    @BeforeEach
    void setUp() throws Exception {
        workflowService = mock(WorkflowService.class);
        executionService = mock(WorkflowExecutionService.class);
        WorkflowConnectionService connectionService = mock(WorkflowConnectionService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        WorkflowModels.TriggerDefinition trigger = new WorkflowModels.TriggerDefinition(1L, "ORDERS", 2L, 7L,
            "hook", "WEBHOOK_TRIGGER", mapper.readTree("{\"connectionId\":9}"));
        when(workflowService.triggerDefinitions()).thenReturn(List.of(trigger));
        when(connectionService.resolved(9L, Set.of("WEBHOOK"))).thenReturn(new WorkflowConnectionService.StoredConnection(
            9L, "HOOK", "Hook", "WEBHOOK", mapper.readTree("{\"secret\":\"top-secret\"}"), 7L, true, null, null));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(executionService.startTriggered(anyString(), any(Map.class), anyString()))
            .thenReturn(new WorkflowModels.RunAccepted("run-1", "QUEUED"));
        service = new WorkflowTriggerService(workflowService, executionService, connectionService, jdbcTemplate, mapper);
    }

    /** 停止测试调度器线程。 */
    @AfterEach
    void tearDown() { service.shutdown(); }

    /** 正确签名必须启动一次已发布工作流。 */
    @Test
    void acceptsSignedWebhook() throws Exception {
        String body = "{\"orderId\":42}";
        WorkflowModels.RunAccepted accepted = service.webhook("ORDERS", "hook", signature("top-secret", body), "evt-1", body);
        assertEquals("run-1", accepted.runId());
        verify(executionService).startTriggered("ORDERS", Map.of("orderId", 42), "WEBHOOK");
    }

    /** 缺失或错误签名不得创建运行记录。 */
    @Test
    void rejectsInvalidSignature() {
        assertThrows(BusinessException.class, () -> service.webhook("ORDERS", "hook", "bad", "evt-2", "{}"));
    }

    /** 生成测试 HMAC 签名。 */
    private String signature(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
