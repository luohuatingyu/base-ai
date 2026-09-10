package com.baseai.platform.deviceagent;

import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.RequiredPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 暴露 Agent 设备池同步和管理端设备配置接口。 */
@RestController
@RequestMapping("/api")
public class DeviceAgentDeviceController {
    private static final String AGENT_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";
    private final DeviceAgentDeviceService deviceService;

    /** 注入设备池服务。 */
    public DeviceAgentDeviceController(DeviceAgentDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** 接收已通过 HMAC 认证的完整匿名设备快照。 */
    @PostMapping("/agent/ios-device/v1/devices/sync")
    public DeviceAgentModels.AgentDeviceInventoryResponse synchronize(
        HttpServletRequest request, @RequestBody DeviceAgentModels.AgentDeviceInventoryRequest body) {
        return deviceService.synchronize(String.valueOf(request.getAttribute(
            DeviceAgentAuthenticationFilter.AGENT_ID_ATTRIBUTE)), body);
    }

    /** 查询一台 Mac Agent 下的 iOS 设备池。 */
    @GetMapping("/automation/device-agents/{agentId:" + AGENT_ID_PATTERN + "}/devices")
    @RequiredPermission("operations:device-agent:execute")
    public List<DeviceAgentModels.AgentDeviceView> devices(@PathVariable String agentId) {
        return deviceService.list(agentId);
    }

    /** 下发只读设备检测命令并返回 202。 */
    @PostMapping("/automation/device-agents/{agentId:" + AGENT_ID_PATTERN + "}/devices/detect")
    @RequiredPermission("operations:device-agent:list")
    public ResponseEntity<DeviceAgentModels.AgentCommandView> detect(@PathVariable String agentId) {
        return ResponseEntity.accepted().body(deviceService.detect(agentId, AuthContext.require().id()));
    }

    /** 更新一台设备的 WDA 本地端口，空值表示自动重新分配。 */
    @PutMapping("/automation/device-agents/{agentId:" + AGENT_ID_PATTERN
        + "}/devices/{deviceId:[a-f0-9]{64}}/wda-port")
    @RequiredPermission("operations:device-agent:update")
    public DeviceAgentModels.AgentDeviceView updateWdaPort(
        @PathVariable String agentId, @PathVariable String deviceId,
        @RequestBody DeviceAgentModels.UpdateAgentDeviceWdaPortRequest body) {
        return deviceService.updateWdaPort(agentId, deviceId, body, AuthContext.require().id());
    }
}
