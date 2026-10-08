package com.baseai.platform.controller;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 验证原著工作台接口使用独立权限，并保留作品、章节、写作和视频提取入口。 */
class AiNovelControllerTest {
    /** 原著接口不能复用聊天或旧版视频剧本权限。 */
    @Test
    void novelEndpointsUseDedicatedPermission() {
        RequiredPermission permission = AiNovelController.class.getAnnotation(RequiredPermission.class);
        assertNotNull(permission);
        assertEquals("ai:novel:invoke", permission.value());
    }

    /** 关键闭环方法必须继续暴露 HTTP 映射，避免只完成前端页面而丢失后端入口。 */
    @Test
    void exposesWritingAndVideoExtractionEndpoints() throws Exception {
        assertPostMapping("write", "/{novelId}/ai/write");
        assertPostMapping("extractToVideo", "/{novelId}/extract-to-video");
        assertNotNull(AiNovelController.class.getDeclaredMethod("createChapter", long.class, java.util.Map.class));
        assertNotNull(AiNovelController.class.getDeclaredMethod("updateChapter", long.class, long.class, java.util.Map.class));
    }

    /** 校验方法上的 POST 路径与产品闭环协议一致。 */
    private void assertPostMapping(String methodName, String path) throws Exception {
        Method method = methodName.equals("write")
            ? AiNovelController.class.getDeclaredMethod(methodName, long.class, com.baseai.platform.service.AiNovelService.WriteRequest.class)
            : AiNovelController.class.getDeclaredMethod(methodName, long.class, com.baseai.platform.service.AiNovelService.ExtractRequest.class);
        org.springframework.web.bind.annotation.PostMapping mapping = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertNotNull(mapping);
        assertEquals(path, mapping.value()[0]);
    }
}
