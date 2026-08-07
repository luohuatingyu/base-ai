package com.baseai.platform.workflow;

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

    /** 注入触发器服务。 */
    public WorkflowWebhookController(WorkflowTriggerService triggerService) { this.triggerService = triggerService; }

    /** 验证 HMAC 签名和事件幂等键后异步启动已发布工作流。 */
    @PostMapping("/{workflowCode}/{nodeId}")
    public ResponseEntity<WorkflowModels.RunAccepted> webhook(
        @PathVariable String workflowCode, @PathVariable String nodeId,
        @RequestHeader(name = "X-Workflow-Signature", required = false) String signature,
        @RequestHeader(name = "X-Event-Id", required = false) String eventId,
        @RequestBody(required = false) String body) {
        return ResponseEntity.accepted().body(triggerService.webhook(workflowCode, nodeId, signature, eventId,
            body == null ? "" : body));
    }
}
