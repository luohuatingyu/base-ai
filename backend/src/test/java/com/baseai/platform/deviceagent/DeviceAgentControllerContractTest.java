package com.baseai.platform.deviceagent;

import com.baseai.platform.security.RequiredPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证设备 Agent 查看、配置、执行和删除权限相互隔离。 */
class DeviceAgentControllerContractTest {
    /** 高风险管理接口必须声明对应细粒度权限。 */
    @Test
    void protectsAutomationManagementActionsWithDedicatedPermissions() throws Exception {
        Map<Method, String> methods = Map.of(
            DeviceAgentManagementController.class.getMethod("createPairing",
                HttpServletRequest.class, DeviceAgentModels.CreatePairingRequest.class),
            "automation:device-agent:create",
            DeviceAgentManagementController.class.getMethod("updateIdaConfig", String.class,
                DeviceAgentModels.UpdateAgentIdaConfigRequest.class),
            "automation:device-agent:update",
            DeviceAgentManagementController.class.getMethod("registryAction", String.class,
                DeviceAgentModels.AgentRegistryActionRequest.class),
            "automation:device-agent:execute",
            DeviceAgentManagementController.class.getMethod("deleteIdaConfig", String.class),
            "automation:device-agent:delete",
            DeviceAgentDeviceController.class.getMethod("updateIdaPort", String.class, String.class,
                DeviceAgentModels.UpdateAgentDeviceIdaPortRequest.class),
            "automation:device-agent:update"
        );

        for (Map.Entry<Method, String> entry : methods.entrySet()) {
            assertEquals(entry.getValue(), entry.getKey().getAnnotation(RequiredPermission.class).value());
        }
    }

    /** 列表和详情默认继承控制器的只读权限。 */
    @Test
    void keepsReadOperationsUnderListPermission() {
        assertEquals("automation:device-agent:list",
            DeviceAgentManagementController.class.getAnnotation(RequiredPermission.class).value());
    }
}
