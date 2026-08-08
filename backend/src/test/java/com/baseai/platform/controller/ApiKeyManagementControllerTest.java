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

    /** 明文 API Key 查询必须复用列表权限，并继续禁止 API Key 凭证访问管理接口。 */
    @Test
    void revealEndpointRemainsBearerOnly() throws Exception {
        Method reveal = ApiKeyManagementController.class.getMethod("reveal", Long.class);

        assertEquals("system:api-key:list", reveal.getAnnotation(RequiredPermission.class).value());
        assertNull(reveal.getAnnotation(ApiKeyEndpoint.class));
    }

    /** 工作流白名单选项属于管理接口，不允许 API Key 自身枚举可执行资源。 */
    @Test
    void workflowOptionsRemainBearerOnly() throws Exception {
        Method options = ApiKeyManagementController.class.getMethod("workflowOptions", Long.class);
        assertEquals("system:api-key:list", options.getAnnotation(RequiredPermission.class).value());
        assertNull(options.getAnnotation(ApiKeyEndpoint.class));
    }

    /** 首批开放接口声明稳定代码和前端国际化键。 */
    @Test
    void businessEndpointsDeclareStableApiKeyCodesAndTranslationKeys() throws Exception {
        Method chat = AiChatController.class.getMethod("chat", AiChatController.ChatRequest.class);
        Method trigger = ApiTriggerController.class.getMethod("trigger", Long.class);

        assertEquals("ai.chat.invoke", chat.getAnnotation(ApiKeyEndpoint.class).code());
        assertEquals("apiKeys.endpointNames.aiChatInvoke", chat.getAnnotation(ApiKeyEndpoint.class).nameKey());
        assertEquals("apiKeys.endpointGroups.ai", chat.getAnnotation(ApiKeyEndpoint.class).groupKey());
        assertEquals("automation.api-trigger.execute", trigger.getAnnotation(ApiKeyEndpoint.class).code());
        assertEquals("apiKeys.endpointNames.apiTriggerExecute", trigger.getAnnotation(ApiKeyEndpoint.class).nameKey());
        assertEquals("apiKeys.endpointGroups.automation", trigger.getAnnotation(ApiKeyEndpoint.class).groupKey());
        assertNotNull(chat.getAnnotation(ApiKeyEndpoint.class));
        assertNotNull(trigger.getAnnotation(ApiKeyEndpoint.class));
    }
}
