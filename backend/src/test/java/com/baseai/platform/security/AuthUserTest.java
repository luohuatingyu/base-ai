package com.baseai.platform.security;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AuthUserTest {
    @Test
    void managePermissionKeepsGranularCompatibility() {
        AuthUser user = new AuthUser(1L, "operator", Set.of("USER_ADMIN"), Set.of("system:user:manage"));
        assertTrue(user.hasPermission("system:user:list"));
        assertTrue(user.hasPermission("system:user:create"));
        assertFalse(user.hasPermission("system:role:list"));
    }
}
