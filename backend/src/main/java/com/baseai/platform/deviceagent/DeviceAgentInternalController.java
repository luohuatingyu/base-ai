package com.baseai.platform.deviceagent;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供 Agent HMAC 通道，不暴露账号、好友或业务任务接口。 */
@RestController
@RequestMapping("/api/agent/ios-device/v1")
public class DeviceAgentInternalController {
    private final DeviceAgentRegistrationService registrationService;
    private final DeviceAgentCommandService commandService;

    /** 注入 Agent 注册配置和命令租约服务。 */
    public DeviceAgentInternalController(DeviceAgentRegistrationService registrationService,
                                         DeviceAgentCommandService commandService) {
        this.registrationService = registrationService;
        this.commandService = commandService;
    }

    /** 使用一次性配对码领取独立 Secret。 */
    @PostMapping("/pairing/claim")
    public DeviceAgentModels.ClaimPairingResponse claim(
        @RequestBody DeviceAgentModels.ClaimPairingRequest body) {
        return registrationService.claim(body);
    }

    /** 拉取功能配置。 */
    @GetMapping("/config")
    public DeviceAgentModels.AgentConfigView config(HttpServletRequest request) {
        return registrationService.agentConfig(agentId(request));
    }

    /** 上报 Agent 与工具链健康状态。 */
    @PostMapping("/health")
    public void health(HttpServletRequest request, @RequestBody DeviceAgentModels.AgentHealthRequest body) {
        registrationService.reportHealth(agentId(request), body);
    }

    /** 上报只读环境诊断结果。 */
    @PostMapping("/diagnostics")
    public void diagnostics(HttpServletRequest request,
                            @RequestBody DeviceAgentModels.AgentDiagnosticsRequest body) {
        registrationService.reportDiagnostics(agentId(request), body);
    }

    /** 领取一条能力匹配的短期命令租约，无命令时返回 204。 */
    @PostMapping("/commands/lease")
    public ResponseEntity<DeviceAgentModels.LeaseCommandResponse> lease(
        HttpServletRequest request, @RequestBody DeviceAgentModels.LeaseCommandRequest body) {
        DeviceAgentModels.LeaseCommandResponse lease = commandService.lease(agentId(request), body);
        return lease == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(lease);
    }

    /** 使用绑定租约上报命令终态。 */
    @PostMapping("/commands/{commandId}/result")
    public void result(HttpServletRequest request, @PathVariable Long commandId,
                       @RequestBody DeviceAgentModels.ReportCommandResultRequest body) {
        commandService.reportResult(agentId(request), commandId, body);
    }

    /** 读取由 HMAC 过滤器注入的可信 Agent 身份。 */
    private String agentId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(DeviceAgentAuthenticationFilter.AGENT_ID_ATTRIBUTE));
    }
}
