package com.baseai.platform.security;

import java.util.Set;

public record AuthUser(Long id, String username, Set<String> roles, Set<String> permissions,
                       AuthenticationType authenticationType, Long credentialId, String credentialName) {
    private static final String DATA_SOURCE_LIST_PERMISSION = "operations:data-source:list";
    private static final Set<String> DATA_SOURCE_MAINTENANCE_PERMISSIONS = Set.of(
        "operations:data-source:create",
        "operations:data-source:update",
        "operations:data-source:delete"
    );

    /** 管理员角色拥有所有平台权限。 */
    public boolean hasPermission(String permission) {
        if (roles.contains("ADMIN") || permissions.contains(permission)) return true;
        if (DATA_SOURCE_MAINTENANCE_PERMISSIONS.contains(permission)
            && permissions.contains(DATA_SOURCE_LIST_PERMISSION)) return true;
        int separator = permission.lastIndexOf(':');
        return separator > 0 && permissions.contains(permission.substring(0, separator) + ":manage");
    }
}
