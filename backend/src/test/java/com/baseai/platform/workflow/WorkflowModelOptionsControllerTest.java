package com.baseai.platform.workflow;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowModelOptionsControllerTest {
    /** 模型选项接口必须保持只读并复用工作流节点查看权限。 */
    @Test
    void modelOptionsRequireWorkflowNodeListPermission() throws Exception {
        Method method = WorkflowController.class.getMethod("modelOptions");

        assertArrayEquals(new String[]{"/model-options"}, method.getAnnotation(GetMapping.class).value());
        assertEquals("workflow:node:list", method.getAnnotation(RequiredPermission.class).value());
    }
}
