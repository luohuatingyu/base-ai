package com.baseai.platform.controller;

import com.baseai.platform.automation.ApiTriggerController;
import com.baseai.platform.security.ApiKeyEndpoint;
import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiKeyManagementControllerTest {
    /** 管理接口使用独立权限且没有 API Key 开放注解。 */
    @Test
    void managementEndpointsRemainBearerOnly() throws Exception {
        Method create = ApiKeyManagementController.class.getMethod("create", com.baseai.platform.service.ApiKeyManagementService.ApiKeyCommand.class);
        assertEquals("system:api-key:create", create.getAnnotation(RequiredPermission.class).value());
        assertNull(create.getAnnotation(ApiKeyEndpoint.class));
    }

    /** 首批仅显式开放 AI 对话和正式接口触发执行。 */
    @Test
    void businessEndpointsDeclareStableApiKeyCodes() throws Exception {
        Method chat = AiChatController.class.getMethod("chat", AiChatController.ChatRequest.class);
        Method trigger = ApiTriggerController.class.getMethod("trigger", Long.class);

        assertEquals("ai.chat.invoke", chat.getAnnotation(ApiKeyEndpoint.class).code());
        assertEquals("automation.api-trigger.execute", trigger.getAnnotation(ApiKeyEndpoint.class).code());
        assertNotNull(chat.getAnnotation(ApiKeyEndpoint.class));
        assertNotNull(trigger.getAnnotation(ApiKeyEndpoint.class));
    }
}
