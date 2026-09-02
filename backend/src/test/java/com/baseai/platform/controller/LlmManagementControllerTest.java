package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.security.SecretRevealAuthorizationService;
import com.baseai.platform.trace.TraceType;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmManagementControllerTest {
    /** 明文密钥查询接口必须要求更新权限、密码二次验证 POST 和无请求快照。 */
    @Test
    void providerApiKeysRequiresUpdatePermission() throws NoSuchMethodException {
        RequiredPermission permission = LlmManagementController.class
            .getMethod("providerApiKeys", Long.class, SecretRevealAuthorizationService.ReauthenticationCommand.class)
            .getAnnotation(RequiredPermission.class);

        assertEquals("model:provider:update", permission.value());
        assertEquals(false, LlmManagementController.class
            .getMethod("providerApiKeys", Long.class, SecretRevealAuthorizationService.ReauthenticationCommand.class)
            .getAnnotation(TraceType.class).captureRequest());
        org.junit.jupiter.api.Assertions.assertNotNull(LlmManagementController.class
            .getMethod("providerApiKeys", Long.class, SecretRevealAuthorizationService.ReauthenticationCommand.class)
            .getAnnotation(PostMapping.class));
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
