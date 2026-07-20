package com.baseai.platform.security;

import java.util.Set;

public record AuthUser(Long id, String username, Set<String> roles, Set<String> permissions) {
    /** 管理员角色拥有所有平台权限。 */
    public boolean hasPermission(String permission) {
        if (roles.contains("ADMIN") || permissions.contains(permission)) return true;
        int separator = permission.lastIndexOf(':');
        return separator > 0 && permissions.contains(permission.substring(0, separator) + ":manage");
    }
}
