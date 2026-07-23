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
}
