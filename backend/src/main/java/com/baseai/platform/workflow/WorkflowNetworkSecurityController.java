package com.baseai.platform.workflow;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.trace.TraceType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供工作流连接器独立出站安全策略的管理接口。 */
@RestController
@RequestMapping("/api/workflow/network-security")
public class WorkflowNetworkSecurityController {
    private final WorkflowNetworkSecurityService securityService;

    /** 注入工作流网络安全配置服务。 */
    public WorkflowNetworkSecurityController(WorkflowNetworkSecurityService securityService) {
        this.securityService = securityService;
    }

    /** 查询当前工作流连接器 Host 与 CIDR 白名单。 */
    @GetMapping
    @RequiredPermission("automation:api-trigger-security:view")
    public WorkflowNetworkSecurityService.ConfigurationView current() { return securityService.current(); }

    /** 更新独立白名单并立即使多实例缓存失效。 */
    @PutMapping
    @RequiredPermission("automation:api-trigger-security:update")
    @TraceType(value = "WORKFLOW_NETWORK_SECURITY_UPDATE", triggerEntry = "MANUAL", captureRequest = false)
    public WorkflowNetworkSecurityService.ConfigurationView update(
        @RequestBody WorkflowNetworkSecurityService.UpdateCommand command) {
        return securityService.update(command);
    }
}
