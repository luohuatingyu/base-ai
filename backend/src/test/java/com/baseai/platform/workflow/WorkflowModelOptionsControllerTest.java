package com.baseai.platform.workflow;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowModelOptionsControllerTest {
    /** 模型选项接口必须保持只读并复用工作流节点查看权限。 */
    @Test
    void modelOptionsRequireWorkflowNodeListPermission() throws Exception {
        Method method = WorkflowController.class.getMethod("modelOptions");

        assertArrayEquals(new String[]{"/model-options"}, method.getAnnotation(GetMapping.class).value());
        assertEquals("workflow:node:list", method.getAnnotation(RequiredPermission.class).value());
    }

    /** 模型路由选项接口必须保持只读并复用工作流节点查看权限。 */
    @Test
    void routeOptionsRequireWorkflowNodeListPermission() throws Exception {
        Method method = WorkflowController.class.getMethod("routeOptions");

        assertArrayEquals(new String[]{"/route-options"}, method.getAnnotation(GetMapping.class).value());
        assertEquals("workflow:node:list", method.getAnnotation(RequiredPermission.class).value());
    }

    /** 邮件路由和连接选项接口必须保持只读并复用节点查看权限。 */
    @Test
    void managedResourceOptionsRequireWorkflowNodeListPermission() throws Exception {
        Map<String, String> paths = Map.of(
            "mailRouteOptions", "/mail-route-options",
            "connectionOptions", "/connection-options"
        );
        for (Map.Entry<String, String> entry : paths.entrySet()) {
            Method method = WorkflowController.class.getMethod(entry.getKey());
            assertArrayEquals(new String[]{entry.getValue()}, method.getAnnotation(GetMapping.class).value());
            assertEquals("workflow:node:list", method.getAnnotation(RequiredPermission.class).value());
        }
    }
}
