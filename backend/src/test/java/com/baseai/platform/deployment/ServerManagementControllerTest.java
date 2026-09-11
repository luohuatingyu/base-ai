package com.baseai.platform.deployment;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 覆盖服务器管理实时监控接口的权限和 HTTP 契约。 */
class ServerManagementControllerTest {
    /** 监控接口必须复用服务器测试权限并保持只读 GET 语义。 */
    @Test
    void protectsMonitorEndpointWithServerTestPermission() throws Exception {
        Method monitor = ServerManagementController.class.getMethod("monitor", Long.class);
        assertArrayEquals(new String[]{"/{id}/monitor"}, monitor.getAnnotation(GetMapping.class).value());
        assertEquals("operations:server:test", monitor.getAnnotation(RequiredPermission.class).value());
    }

    /** 凭据写入和明文查看都有权限检查且禁止记录请求快照。 */
    @Test
    void protectsCredentialEndpointsAndDisablesSecretSnapshots() throws Exception {
        for (String name : new String[]{"create", "update", "reveal"}) {
            Method method = java.util.Arrays.stream(ServerCredentialController.class.getMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            assertEquals(false, method.getAnnotation(com.baseai.platform.trace.TraceType.class).captureRequest());
            assertEquals("operations:server:" + switch (name) { case "create" -> "create"; case "update" -> "update"; default -> "list"; },
                method.getAnnotation(RequiredPermission.class).value());
        }
        assertEquals("operations:server:delete", ServerCredentialController.class.getMethod("delete", Long.class)
            .getAnnotation(RequiredPermission.class).value());
    }
}
