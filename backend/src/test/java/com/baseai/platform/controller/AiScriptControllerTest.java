package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 验证 AI 剧本接口的权限边界和请求协议，避免独立入口误用聊天权限。 */
class AiScriptControllerTest {
    /** 剧本生成必须使用独立权限，方法也必须暴露固定生成路径。 */
    @Test
    void scriptGenerationUsesDedicatedPermission() throws Exception {
        RequiredPermission classPermission = AiScriptController.class.getAnnotation(RequiredPermission.class);
        assertNotNull(classPermission);
        assertEquals("ai:script:invoke", classPermission.value());
        Method method = AiScriptController.class.getDeclaredMethod("generate", AiScriptController.ScriptRequest.class);
        assertNotNull(method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class));
        assertEquals("generate", method.getName());
    }
}
