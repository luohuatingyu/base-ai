package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.trace.TraceType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MailManagementControllerContractTest {
    /** 邮箱和邮件路由接口必须使用独立 CRUD 权限。 */
    @Test
    void protectsMailManagementEndpoints() {
        Map<String, String> permissions = Map.ofEntries(
            Map.entry("accounts", "system:mail:account:list"), Map.entry("accountOptions", "system:mail:route:list"),
            Map.entry("accountPassword", "system:mail:account:update"),
            Map.entry("createAccount", "system:mail:account:create"), Map.entry("updateAccount", "system:mail:account:update"),
            Map.entry("deleteAccount", "system:mail:account:delete"), Map.entry("routes", "system:mail:route:list"),
            Map.entry("createRoute", "system:mail:route:create"), Map.entry("updateRoute", "system:mail:route:update"),
            Map.entry("deleteRoute", "system:mail:route:delete"), Map.entry("testRoute", "system:mail:route:update")
        );
        for (Map.Entry<String, String> entry : permissions.entrySet()) {
            Method method = java.util.Arrays.stream(MailManagementController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(entry.getKey())).findFirst().orElseThrow();
            RequiredPermission permission = method.getAnnotation(RequiredPermission.class);
            assertNotNull(permission, entry.getKey());
            assertEquals(entry.getValue(), permission.value(), entry.getKey());
        }
    }

    /** 密码和收件人配置写接口不得保存请求快照。 */
    @Test
    void disablesSensitiveConfigurationSnapshots() {
        for (String name : new String[]{"createAccount", "updateAccount", "createRoute", "updateRoute", "testRoute"}) {
            Method method = java.util.Arrays.stream(MailManagementController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            TraceType traceType = method.getAnnotation(TraceType.class);
            assertNotNull(traceType, name);
            assertFalse(traceType.captureRequest(), name);
        }
    }
}
