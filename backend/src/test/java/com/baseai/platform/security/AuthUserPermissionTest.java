package com.baseai.platform.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthUserPermissionTest {
    /** 数据源查看权限必须自动覆盖新增、编辑和删除操作。 */
    @ParameterizedTest
    @ValueSource(strings = {
        "operations:data-source:create",
        "operations:data-source:update",
        "operations:data-source:delete"
    })
    void dataSourceListPermissionImpliesMaintenancePermissions(String permission) {
        AuthUser user = user(Set.of("operations:data-source:list"));

        assertTrue(user.hasPermission(permission));
    }

    /** 数据源查看权限不得隐式授予连接测试或其他资源操作。 */
    @Test
    void dataSourceListPermissionKeepsUnrelatedPermissionsIndependent() {
        AuthUser user = user(Set.of("operations:data-source:list"));

        assertFalse(user.hasPermission("operations:data-source:test"));
        assertFalse(user.hasPermission("operations:data-sync:create"));
    }

    /** 创建仅包含指定权限的普通用户身份。 */
    private AuthUser user(Set<String> permissions) {
        return new AuthUser(7L, "operator", Set.of("USER"), permissions,
            AuthenticationType.TOKEN, null, null);
    }
}
