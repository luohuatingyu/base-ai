package com.baseai.platform.automation;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiTriggerSecurityConfigurationControllerTest {
    /** 查询接口必须要求独立的安全配置查看权限。 */
    @Test
    void currentRequiresViewPermission() throws Exception {
        Method method = ApiTriggerSecurityConfigurationController.class.getMethod("current");

        assertEquals("automation:api-trigger-security:view", method.getAnnotation(RequiredPermission.class).value());
    }

    /** 更新接口必须要求独立的安全配置更新权限。 */
    @Test
    void updateRequiresUpdatePermission() throws Exception {
        Method method = ApiTriggerSecurityConfigurationController.class.getMethod("update",
            ApiTriggerSecurityConfigurationService.UpdateCommand.class);

        assertEquals("automation:api-trigger-security:update", method.getAnnotation(RequiredPermission.class).value());
    }
}
