package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmManagementControllerTest {
    /** 明文密钥查询接口必须要求供应商更新权限。 */
    @Test
    void providerApiKeysRequiresUpdatePermission() throws NoSuchMethodException {
        RequiredPermission permission = LlmManagementController.class
            .getMethod("providerApiKeys", Long.class)
            .getAnnotation(RequiredPermission.class);

        assertEquals("model:provider:update", permission.value());
    }

    /** 路由级同步接口必须继续要求路由更新权限。 */
    @Test
    void routeSyncRequiresUpdatePermission() throws NoSuchMethodException {
        RequiredPermission permission = LlmManagementController.class
            .getMethod("syncRoutes", com.baseai.platform.service.LlmManagementService.RouteSyncCommand.class)
            .getAnnotation(RequiredPermission.class);

        assertEquals("model:route:update", permission.value());
    }

    /** 批量路由同步接口必须要求路由更新权限。 */
    @Test
    void batchRouteSyncRequiresUpdatePermission() throws NoSuchMethodException {
        RequiredPermission permission = LlmManagementController.class
            .getMethod("syncRouteBatch", com.baseai.platform.service.LlmManagementService.RouteBatchSyncCommand.class)
            .getAnnotation(RequiredPermission.class);

        assertEquals("model:route:update", permission.value());
    }

    /** 删除当前路由供应商必须继续要求路由更新权限。 */
    @Test
    void removeRouteProviderRequiresUpdatePermission() throws NoSuchMethodException {
        RequiredPermission permission = LlmManagementController.class
            .getMethod("removeProvider", Long.class, Long.class)
            .getAnnotation(RequiredPermission.class);

        assertEquals("model:route:update", permission.value());
    }
}
