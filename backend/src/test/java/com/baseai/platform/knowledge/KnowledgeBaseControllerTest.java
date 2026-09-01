package com.baseai.platform.knowledge;

import com.baseai.platform.security.RequiredPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeBaseControllerTest {
    /** 新增管理读取接口必须沿用知识库列表权限。 */
    @Test
    void managementEndpointsRequireListPermission() throws Exception {
        assertPermission("management","knowledge:base:list",String.class,Boolean.class,String.class,int.class,int.class);
        assertPermission("documentPage","knowledge:base:list",Long.class,String.class,String.class,int.class,int.class);
    }

    /** 启停与批量删除必须沿用知识库更新权限。 */
    @Test
    void maintenanceEndpointsRequireUpdatePermission() throws Exception {
        assertPermission("setEnabled","knowledge:base:update",Long.class,KnowledgeBaseService.EnabledCommand.class);
        assertPermission("deleteDocuments","knowledge:base:update",Long.class,KnowledgeBaseService.BatchDeleteCommand.class);
    }

    /** 读取控制器方法上的权限声明。 */
    private void assertPermission(String name,String permission,Class<?>...parameterTypes)throws Exception {
        Method method=KnowledgeBaseController.class.getMethod(name,parameterTypes);assertEquals(permission,method.getAnnotation(RequiredPermission.class).value());
    }
}
