package com.baseai.platform.datasource;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.workflow.WorkflowModels;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSourceControllerTest {
    /** 数据源管理接口必须使用独立运维路径和细分权限。 */
    @Test
    void exposesDedicatedDataSourceCrudPermissions() throws Exception {
        assertArrayEquals(new String[]{"/api/data-sources"},
            DataSourceController.class.getAnnotation(RequestMapping.class).value());
        Map<String, String> permissions = Map.of(
            "list", "operations:data-source:list",
            "create", "operations:data-source:create",
            "update", "operations:data-source:update",
            "delete", "operations:data-source:delete",
            "test", "operations:data-source:test"
        );
        for (Map.Entry<String, String> entry : permissions.entrySet()) {
            Method method = switch (entry.getKey()) {
                case "create" -> DataSourceController.class.getMethod("create", WorkflowModels.ConnectionCommand.class);
                case "update" -> DataSourceController.class.getMethod("update", Long.class, WorkflowModels.ConnectionCommand.class);
                case "delete", "test" -> DataSourceController.class.getMethod(entry.getKey(), Long.class);
                default -> DataSourceController.class.getMethod(entry.getKey());
            };
            assertEquals(entry.getValue(), method.getAnnotation(RequiredPermission.class).value());
        }
    }

    /** 插件组件和 OAuth 生命周期必须归属数据源权限，不再依赖工作流维护权限。 */
    @Test
    void protectsPluginDataSourceLifecycle() throws Exception {
        Method options = DataSourceController.class.getMethod("pluginComponentOptions");
        Method authorize = DataSourceController.class.getMethod("authorizePlugin", Long.class,
            WorkflowModels.PluginOAuthAuthorizeCommand.class);
        Method callback = DataSourceController.class.getMethod("pluginOAuthCallback",
            WorkflowModels.PluginOAuthCallbackCommand.class);

        assertArrayEquals(new String[]{"/plugin-component-options"}, options.getAnnotation(GetMapping.class).value());
        assertEquals("operations:data-source:list", options.getAnnotation(RequiredPermission.class).value());
        assertArrayEquals(new String[]{"/{id}/oauth/authorize"}, authorize.getAnnotation(PostMapping.class).value());
        assertEquals("operations:data-source:update", authorize.getAnnotation(RequiredPermission.class).value());
        assertArrayEquals(new String[]{"/plugin-oauth/callback"}, callback.getAnnotation(PostMapping.class).value());
        assertEquals("operations:data-source:update", callback.getAnnotation(RequiredPermission.class).value());
    }

    /** CRUD 方法必须保持预期 HTTP 动词，避免前端迁移后命中错误接口。 */
    @Test
    void keepsCrudHttpMethodContracts() throws Exception {
        assertArrayEquals(new String[]{}, DataSourceController.class.getMethod("list").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{}, DataSourceController.class.getMethod("create",
            WorkflowModels.ConnectionCommand.class).getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/{id}"}, DataSourceController.class.getMethod("update", Long.class,
            WorkflowModels.ConnectionCommand.class).getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/{id}"}, DataSourceController.class.getMethod("delete",
            Long.class).getAnnotation(DeleteMapping.class).value());
    }

    /** 状态查询接口必须复用测试权限，避免状态探测绕过权限控制。 */
    @Test
    void protectsStatusEndpointWithTestPermission() throws Exception {
        Method status = DataSourceController.class.getMethod("status", Long.class);
        assertArrayEquals(new String[]{"/{id}/status"}, status.getAnnotation(GetMapping.class).value());
        assertEquals("operations:data-source:test", status.getAnnotation(RequiredPermission.class).value());
    }
}
