package com.baseai.platform.controller;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.security.RequiredPermission;
import com.baseai.platform.service.LlmUsageService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class LlmUsageControllerTest {
    /** 模型使用汇总接口必须受管理员监控权限保护。 */
    @Test
    void usageEndpointsRequireAdminMonitoringPermission() throws NoSuchMethodException {
        RequiredPermission classPermission = LlmUsageController.class.getAnnotation(RequiredPermission.class);
        assertNotNull(classPermission);
        assertEquals("operations:llm-usage:view", classPermission.value());
        assertNotNull(LlmUsageController.class.getMethod("summary", String.class, String.class, String.class,
            String.class, String.class, String.class).getAnnotation(org.springframework.web.bind.annotation.GetMapping.class));
        assertNotNull(LlmUsageController.class.getMethod("records", String.class, String.class, String.class,
            String.class, String.class, String.class, int.class, int.class));
    }

    /** 普通用户调用监控接口必须在进入查询服务前被拒绝。 */
    @Test
    void ordinaryUserCannotReadUsage() {
        AuthContext.set(new AuthUser(8L, "user", Set.of("USER"), Set.of(),
            AuthenticationType.TOKEN, null, null));
        try {
            LlmUsageController controller = new LlmUsageController(mock(LlmUsageService.class));
            assertThrows(BusinessException.class, () -> controller.summary(null, null, null, null, null, null));
        } finally {
            AuthContext.clear();
        }
    }
}
