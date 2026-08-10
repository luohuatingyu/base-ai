package com.baseai.platform.workflow;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowNodeDocumentationControllerTest {
    /** 节点文档接口必须保持只读并使用独立文档权限。 */
    @Test
    void nodeDocumentationUsesDedicatedReadPermission() throws Exception {
        Method method = WorkflowController.class.getMethod("nodeDocumentation");

        assertArrayEquals(new String[]{"/node-docs"}, method.getAnnotation(GetMapping.class).value());
        assertEquals("workflow:node:docs", method.getAnnotation(RequiredPermission.class).value());
    }
}
