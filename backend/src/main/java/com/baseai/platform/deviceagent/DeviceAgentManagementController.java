package com.baseai.platform.deviceagent;

import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.ClientIpResolver;
import com.baseai.platform.security.RequiredPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供设备 Agent 配对、配置、只读诊断和自维护命令管理接口。 */
@RestController
@RequestMapping("/api/automation/device-agents")
@RequiredPermission("automation:device-agent:list")
public class DeviceAgentManagementController {
    private static final String AGENT_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";
    private final DeviceAgentRegistrationService registrationService;
    private final DeviceAgentCommandService commandService;
    private final DeviceAgentAutomationConfigService automationConfigService;
    private final DeviceAgentRegistryService registryService;
    private final ClientIpResolver clientIpResolver;

    /** 注入注册、命令和可信客户端地址解析服务。 */
    public DeviceAgentManagementController(DeviceAgentRegistrationService registrationService,
                                           DeviceAgentCommandService commandService,
                                           DeviceAgentAutomationConfigService automationConfigService,
                                           DeviceAgentRegistryService registryService,
                                           ClientIpResolver clientIpResolver) {
        this.registrationService = registrationService;
        this.commandService = commandService;
        this.automationConfigService = automationConfigService;
        this.registryService = registryService;
        this.clientIpResolver = clientIpResolver;
    }

    /** 返回管理员可继续编辑的 Agent 身份建议。 */
    @GetMapping("/pairing/identity-suggestion")
    public DeviceAgentModels.AgentIdentitySuggestion identitySuggestion() {
        return registrationService.identitySuggestion();
    }

    /** 创建一次性配对码。 */
    @PostMapping("/pairing")
    @RequiredPermission("automation:device-agent:create")
    public DeviceAgentModels.CreatePairingResponse createPairing(HttpServletRequest request,
                                                                  @RequestBody DeviceAgentModels.CreatePairingRequest body) {
        return registrationService.createPairing(body, userId(), clientIpResolver.resolve(request));
    }

    /** 查询配对码元数据，绝不返回明文或摘要。 */
    @GetMapping("/pairing")
    public DeviceAgentModels.PageResult<DeviceAgentModels.PairingCodeView> pairingCodes(
        @RequestParam(required = false) String agentId,
        @RequestParam(defaultValue = "false") boolean includeInactive,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size) {
        return registrationService.pairings(agentId, includeInactive, page, size);
    }

    /** 查看仍有效配对码的明文。 */
    @GetMapping("/pairing/{pairingId}/code")
    public DeviceAgentModels.PairingCodeSecretView pairingCode(@PathVariable Long pairingId) {
        return registrationService.pairingSecret(pairingId);
    }

    /** 撤销尚未使用的配对码。 */
    @DeleteMapping("/pairing/{pairingId}")
    @RequiredPermission("automation:device-agent:delete")
    public void revokePairing(HttpServletRequest request, @PathVariable Long pairingId) {
        registrationService.revokePairing(pairingId, userId(), clientIpResolver.resolve(request));
    }

    /** 删除已经失效的配对记录。 */
    @DeleteMapping("/pairing/{pairingId}/record")
    @RequiredPermission("automation:device-agent:delete")
    public void deletePairingRecord(@PathVariable Long pairingId) {
        registrationService.deletePairingRecord(pairingId);
    }

    /** 为已有 Agent 补发配对码。 */
    @PostMapping("/{agentId:" + AGENT_ID_PATTERN + "}/pairing")
    @RequiredPermission("automation:device-agent:create")
    public DeviceAgentModels.CreatePairingResponse reissuePairing(HttpServletRequest request,
                                                                   @PathVariable String agentId) {
        DeviceAgentModels.AgentRegistrationView current = registrationService.registration(agentId);
        List<String> features = new java.util.ArrayList<>();
        if ("ENABLED".equals(current.featureDiagnostics())) features.add("READ_ONLY_DIAGNOSTICS");
        if ("ENABLED".equals(current.featureAutomation())) features.add("APPIUM_IDA_AUTOMATION");
        if ("ENABLED".equals(current.featureAutostart())) features.add("AUTOSTART");
        return registrationService.createPairing(new DeviceAgentModels.CreatePairingRequest(
            agentId, features, current.backendUrl(), current.deviceName()), userId(),
            clientIpResolver.resolve(request));
    }

    /** 查询单个 Agent。 */
    @GetMapping("/{agentId:" + AGENT_ID_PATTERN + "}")
    public DeviceAgentModels.AgentRegistrationView agent(@PathVariable String agentId) {
        return registrationService.registration(agentId);
    }

    /** 将已配对 Agent 设为唯一默认实例。 */
    @PostMapping("/{agentId:" + AGENT_ID_PATTERN + "}/default")
    @RequiredPermission("automation:device-agent:update")
    public void setDefault(@PathVariable String agentId) { registrationService.setDefault(agentId); }

    /** 更新回连地址，并在已配对时下发原子改址命令。 */
    @PutMapping("/{agentId:" + AGENT_ID_PATTERN + "}/backend-url")
    @RequiredPermission("automation:device-agent:update")
    public DeviceAgentModels.UpdateAgentBackendUrlResult updateBackendUrl(
        @PathVariable String agentId, @RequestBody DeviceAgentModels.UpdateAgentBackendUrlRequest body) {
        boolean paired = registrationService.updateBackendUrl(agentId, body == null ? null : body.backendUrl());
        // 只下发归一化后实际落库的地址：清空回退全局默认时 Agent 没有可执行的
        // 目标地址，改址命令会被 Agent 拒绝，这种情况交给管理页的本机改址命令处理
        String effectiveUrl = registrationService.registration(agentId).backendUrl();
        DeviceAgentModels.AgentCommandView command =
            paired && effectiveUrl != null ? commandService.create(
            new DeviceAgentModels.CreateCommandRequest(agentId, "UPDATE_BACKEND_URL",
                java.util.Collections.singletonMap("backendUrl", effectiveUrl)), userId()) : null;
        return new DeviceAgentModels.UpdateAgentBackendUrlResult(effectiveUrl,
            command != null, command == null ? null : command.id());
    }

    /** 更新设备 Agent 可读名称。 */
    @PutMapping("/{agentId:" + AGENT_ID_PATTERN + "}/device-name")
    @RequiredPermission("automation:device-agent:update")
    public void updateDeviceName(@PathVariable String agentId,
                                 @RequestBody DeviceAgentModels.UpdateAgentDeviceNameRequest body) {
        registrationService.updateDeviceName(agentId, body == null ? null : body.deviceName());
    }

    /** 查询最近心跳与只读诊断得出的就绪状态。 */
    @GetMapping("/{agentId:" + AGENT_ID_PATTERN + "}/readiness")
    public DeviceAgentModels.AgentReadinessView readiness(@PathVariable String agentId) {
        return registrationService.readiness(agentId);
    }

    /** 更新设备管理功能开关。 */
    @PutMapping("/{agentId:" + AGENT_ID_PATTERN + "}/features")
    @RequiredPermission("automation:device-agent:update")
    public void updateFeatures(@PathVariable String agentId,
                               @RequestBody DeviceAgentModels.UpdateFeaturesRequest body) {
        registrationService.updateFeatures(agentId, body);
    }

    /** 撤销 Agent 和全部未完成命令。 */
    @PostMapping("/{agentId:" + AGENT_ID_PATTERN + "}/revoke")
    @RequiredPermission("automation:device-agent:delete")
    public void revoke(HttpServletRequest request, @PathVariable String agentId,
                       @RequestBody(required = false) DeviceAgentModels.RevokeAgentRequest body) {
        registrationService.revoke(agentId, body == null ? null : body.reason(), userId(),
            clientIpResolver.resolve(request));
    }

    /** 删除 Agent 注册与设备配置，审计记录保留。 */
    @DeleteMapping("/{agentId:" + AGENT_ID_PATTERN + "}")
    @RequiredPermission("automation:device-agent:delete")
    public void delete(HttpServletRequest request, @PathVariable String agentId) {
        registrationService.delete(agentId, userId(), clientIpResolver.resolve(request));
    }

    /** 创建白名单内管理命令。 */
    @PostMapping("/commands")
    @RequiredPermission("automation:device-agent:execute")
    public DeviceAgentModels.AgentCommandView createCommand(
        @RequestBody DeviceAgentModels.CreateCommandRequest body) {
        return commandService.create(body, userId());
    }

    /** 查询单条命令状态。 */
    @GetMapping("/commands/{commandId}")
    public DeviceAgentModels.AgentCommandView command(@PathVariable Long commandId) {
        return commandService.get(commandId);
    }

    /** 查询 Agent 最近管理命令。 */
    @GetMapping("/{agentId:" + AGENT_ID_PATTERN + "}/commands")
    public List<DeviceAgentModels.AgentCommandView> commands(@PathVariable String agentId) {
        return commandService.list(agentId);
    }

    /** 取消未完成命令。 */
    @PostMapping("/commands/{commandId}/cancel")
    @RequiredPermission("automation:device-agent:execute")
    public void cancel(@PathVariable Long commandId) { commandService.cancel(commandId); }

    /** 查询已解密但不含私钥材料的 IDA 配置。 */
    @GetMapping("/{agentId:" + AGENT_ID_PATTERN + "}/ida-config")
    public DeviceAgentModels.AgentIdaConfigView idaConfig(@PathVariable String agentId) {
        return automationConfigService.get(agentId);
    }

    /** 加密保存 IDA 签名和本地服务配置。 */
    @PutMapping("/{agentId:" + AGENT_ID_PATTERN + "}/ida-config")
    @RequiredPermission("automation:device-agent:update")
    public DeviceAgentModels.AgentIdaConfigView updateIdaConfig(
        @PathVariable String agentId, @RequestBody DeviceAgentModels.UpdateAgentIdaConfigRequest body) {
        return automationConfigService.update(agentId, body, userId());
    }

    /** 查询通用设备操作速度档位。 */
    @GetMapping("/{agentId:" + AGENT_ID_PATTERN + "}/operation-speed")
    public DeviceAgentModels.AgentOperationSpeedView operationSpeed(@PathVariable String agentId) {
        return automationConfigService.getOperationSpeed(agentId);
    }

    /** 保存通用设备操作速度档位并通知 Agent 热加载。 */
    @PutMapping("/{agentId:" + AGENT_ID_PATTERN + "}/operation-speed")
    @RequiredPermission("automation:device-agent:update")
    public DeviceAgentModels.AgentOperationSpeedView updateOperationSpeed(
        @PathVariable String agentId, @RequestBody DeviceAgentModels.UpdateAgentOperationSpeedRequest body) {
        return automationConfigService.updateOperationSpeed(agentId, body, userId());
    }

    /** 删除 IDA 配置并恢复安全默认值。 */
    @DeleteMapping("/{agentId:" + AGENT_ID_PATTERN + "}/ida-config")
    @RequiredPermission("automation:device-agent:delete")
    public void deleteIdaConfig(@PathVariable String agentId) {
        automationConfigService.delete(agentId, userId());
    }

    /** 查询 Remote XPC Registry 期望配置和脱敏状态。 */
    @GetMapping("/{agentId:" + AGENT_ID_PATTERN + "}/registry")
    public DeviceAgentModels.AgentRegistryView registry(@PathVariable String agentId) {
        return registryService.get(agentId);
    }

    /** 更新 Remote XPC Registry 端口覆盖值。 */
    @PutMapping("/{agentId:" + AGENT_ID_PATTERN + "}/registry")
    @RequiredPermission("automation:device-agent:update")
    public DeviceAgentModels.AgentRegistryView updateRegistry(
        @PathVariable String agentId, @RequestBody DeviceAgentModels.UpdateAgentRegistryRequest body) {
        return registryService.update(agentId, body, userId());
    }

    /** 下发 Registry 上线、下线或重建动作。 */
    @PostMapping("/{agentId:" + AGENT_ID_PATTERN + "}/registry/actions")
    @RequiredPermission("automation:device-agent:execute")
    public DeviceAgentModels.AgentCommandView registryAction(
        @PathVariable String agentId, @RequestBody DeviceAgentModels.AgentRegistryActionRequest body) {
        return registryService.action(agentId, body, userId());
    }

    /** 分页查询 Agent。 */
    @GetMapping
    public DeviceAgentModels.PageResult<DeviceAgentModels.AgentRegistrationView> agents(
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String status) {
        return registrationService.registrations(page, size, search, status);
    }

    /** 返回当前登录用户 ID，不使用固定用户回退。 */
    private Long userId() { return AuthContext.require().id(); }
}
