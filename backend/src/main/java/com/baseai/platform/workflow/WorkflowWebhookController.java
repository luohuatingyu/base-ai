package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 接收不依赖登录态、但必须通过节点独立签名校验的工作流 Webhook。 */
@RestController
@RequestMapping("/api/workflow-hooks")
public class WorkflowWebhookController {
    private final WorkflowTriggerService triggerService;
    private final WorkflowWebhookRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final int maxBodyBytes;

    /** 注入触发器服务。 */
    public WorkflowWebhookController(WorkflowTriggerService triggerService, WorkflowWebhookRateLimiter rateLimiter,
                                     ClientIpResolver clientIpResolver, PlatformProperties properties) {
        this.triggerService = triggerService; this.rateLimiter = rateLimiter; this.clientIpResolver = clientIpResolver;
        this.maxBodyBytes = Math.max(1, properties.getWorkflow().getWebhookMaxBodyBytes());
    }

    /** 验证 HMAC 签名和事件幂等键后异步启动已发布工作流。 */
    @PostMapping("/{workflowCode}/{nodeId}")
    public ResponseEntity<WorkflowModels.RunAccepted> webhook(
        @PathVariable String workflowCode, @PathVariable String nodeId,
        @RequestHeader(name = "X-Workflow-Signature", required = false) String signature,
        @RequestHeader(name = "X-Event-Id", required = false) String eventId,
        HttpServletRequest request) {
        if (workflowCode == null || !workflowCode.matches("[A-Za-z][A-Za-z0-9_-]{1,79}")
            || nodeId == null || !nodeId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw BusinessException.notFound("workflow.triggerNotFound");
        }
        rateLimiter.check(workflowCode, nodeId, clientIpResolver.resolve(request));
        return ResponseEntity.accepted().body(triggerService.webhook(workflowCode, nodeId, signature, eventId,
            readBody(request)));
    }

    /** 在分配大字符串前限制公开请求正文并保留签名所需的原始字节。 */
    private byte[] readBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > maxBodyBytes) throw new BusinessException(413, "workflow.webhookBodyTooLarge");
        try {
            byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
            if (body.length > maxBodyBytes) throw new BusinessException(413, "workflow.webhookBodyTooLarge");
            return body;
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("workflow.webhookBodyInvalid"); }
    }
}
