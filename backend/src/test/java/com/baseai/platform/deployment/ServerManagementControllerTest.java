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
}
