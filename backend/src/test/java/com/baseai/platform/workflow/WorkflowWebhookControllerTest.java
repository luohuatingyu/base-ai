package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowWebhookControllerTest {
    private WorkflowTriggerService triggerService;
    private WorkflowWebhookRateLimiter rateLimiter;
    private ClientIpResolver clientIpResolver;
    private WorkflowWebhookController controller;

    /** 创建 16 字节正文上限的公开入口。 */
    @BeforeEach
    void setUp() {
        triggerService = mock(WorkflowTriggerService.class);
        rateLimiter = mock(WorkflowWebhookRateLimiter.class);
        clientIpResolver = mock(ClientIpResolver.class);
        PlatformProperties properties = new PlatformProperties();
        properties.getWorkflow().setWebhookMaxBodyBytes(16);
        controller = new WorkflowWebhookController(triggerService, rateLimiter, clientIpResolver, properties);
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.9");
    }

    /** 正文必须在限流后以原始字节交给签名服务。 */
    @Test
    void rateLimitsAndPreservesRawBody() {
        byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest(); request.setContent(body);
        when(triggerService.webhook("ORDERS", "hook", "sig", "evt-1", body))
            .thenReturn(new WorkflowModels.RunAccepted("run-1", "QUEUED"));

        controller.webhook("ORDERS", "hook", "sig", "evt-1", request);

        verify(rateLimiter).check("ORDERS", "hook", "203.0.113.9");
        verify(triggerService).webhook("ORDERS", "hook", "sig", "evt-1", body);
        assertArrayEquals(body, request.getContentAsByteArray());
    }

    /** 超过路由专用上限的公开正文必须返回 413 且不启动工作流。 */
    @Test
    void rejectsOversizedBodyBeforeWorkflowExecution() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("0123456789abcdefg".getBytes(StandardCharsets.UTF_8));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> controller.webhook("ORDERS", "hook", "sig", "evt-1", request));

        assertEquals(413, exception.getStatus());
    }
}
