package com.baseai.platform.workflow;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowModelOptionsControllerTest {
    /** 模型选项接口必须保持只读并复用工作流节点查看权限。 */
    @Test
    void modelOptionsRequireWorkflowNodeListPermission() throws Exception {
        Method method = WorkflowController.class.getMethod("modelOptions",String.class,String.class);

        assertArrayEquals(new String[]{"/model-options"}, method.getAnnotation(GetMapping.class).value());
        assertEquals("workflow:node:list", method.getAnnotation(RequiredPermission.class).value());
    }

    /** 模型路由选项接口必须保持只读并复用工作流节点查看权限。 */
    @Test
    void routeOptionsRequireWorkflowNodeListPermission() throws Exception {
        Method method = WorkflowController.class.getMethod("routeOptions",String.class,String.class);

        assertArrayEquals(new String[]{"/route-options"}, method.getAnnotation(GetMapping.class).value());
        assertEquals("workflow:node:list", method.getAnnotation(RequiredPermission.class).value());
    }

    /** 模型类型选择与文档兼容目录必须使用各自只读权限。 */
    @Test
    void modelCompatibilityEndpointsUseSeparatedReadPermissions() throws Exception {
        Method options=WorkflowController.class.getMethod("modelTypeOptions",String.class);
        Method documentation=WorkflowController.class.getMethod("nodeModelCompatibility");
        assertArrayEquals(new String[]{"/model-type-options"},options.getAnnotation(GetMapping.class).value());
        assertEquals("workflow:node:list",options.getAnnotation(RequiredPermission.class).value());
        assertArrayEquals(new String[]{"/node-model-compatibility"},documentation.getAnnotation(GetMapping.class).value());
        assertEquals("workflow:node:docs",documentation.getAnnotation(RequiredPermission.class).value());
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

    /** 市场浏览复用查看权限，导入必须使用独立权限。 */
    @Test
    void marketplaceEndpointsUseSeparatedPermissions() throws Exception {
        Method list = WorkflowController.class.getMethod("marketplaceNodes", String.class, String.class,
            String.class, int.class, int.class, boolean.class);
        Method imports = WorkflowController.class.getMethod("importMarketplaceNodes", String.class,
            WorkflowModels.MarketplaceImportCommand.class);

        assertArrayEquals(new String[]{"/node-marketplaces/{source}/nodes"}, list.getAnnotation(GetMapping.class).value());
        assertEquals("workflow:node:list", list.getAnnotation(RequiredPermission.class).value());
        assertArrayEquals(new String[]{"/node-marketplaces/{source}/imports"}, imports.getAnnotation(PostMapping.class).value());
        assertEquals("workflow:node:import", imports.getAnnotation(RequiredPermission.class).value());
    }
}
