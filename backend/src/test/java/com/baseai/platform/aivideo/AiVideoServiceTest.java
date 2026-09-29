package com.baseai.platform.aivideo;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 验证 AI 视频工作台的细粒度权限和输入边界。 */
class AiVideoServiceTest {
    private final AiVideoService service = new AiVideoService(mock(JdbcTemplate.class));

    /** 每个用例结束后清理线程上下文，避免权限状态串扰。 */
    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    /** 角色资产权限应允许同类资产操作。 */
    @Test
    void itemPermissionAllowsItemCreate() {
        AuthContext.set(user(Set.of("ai:video:item:create")));

        assertDoesNotThrow(() -> service.requirePermission("assets", "create", "ITEM", null));
    }

    /** 角色资产权限不应跨越到其他资产分类。 */
    @Test
    void itemPermissionRejectsSceneCreate() {
        AuthContext.set(user(Set.of("ai:video:item:create")));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.requirePermission("assets", "create", "SCENE", null));

        assertEquals(403, exception.getStatus());
        assertEquals("auth.permissionDenied", exception.getMessageKey());
    }

    /** 非法资源名称必须在进入动态 SQL 前被拒绝。 */
    @Test
    void invalidResourceIsRejected() {
        AuthContext.set(user(Set.of("ai:video:dashboard:list")));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.requirePermission("unknown", "list"));

        assertEquals("aiVideo.resourceInvalid", exception.getMessageKey());
    }

    /** 未认证请求不能绕过工作台权限校验。 */
    @Test
    void missingAuthenticationIsRejected() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.requirePermission("projects", "list"));

        assertEquals(401, exception.getStatus());
        assertEquals("auth.required", exception.getMessageKey());
    }

    /** 构造测试用户，保持与会话认证使用的字段一致。 */
    private AuthUser user(Set<String> permissions) {
        return new AuthUser(7L, "ai-video-user", Set.of("USER"), permissions,
            AuthenticationType.TOKEN, null, null);
    }
}
