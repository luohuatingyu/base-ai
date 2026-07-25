package com.baseai.platform.security;

import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AuthUserFactory {
    /** 根据登录用户构造 Bearer Token 请求身份。 */
    public AuthUser fromToken(UserAccount user) {
        return create(user, AuthenticationType.TOKEN, null, null);
    }

    /** 根据绑定用户和 API Key 构造机器调用身份。 */
    public AuthUser fromApiKey(UserAccount user, Long apiKeyId, String apiKeyName) {
        return create(user, AuthenticationType.API_KEY, apiKeyId, apiKeyName);
    }

    /** 从用户启用角色和菜单构造权限快照。 */
    private AuthUser create(UserAccount user, AuthenticationType type, Long credentialId, String credentialName) {
        Set<Role> enabledRoles = user.getRoles().stream()
            .filter(role -> Boolean.TRUE.equals(role.getEnabled())).collect(Collectors.toSet());
        Set<String> roles = enabledRoles.stream().map(Role::getCode).collect(Collectors.toSet());
        Set<String> permissions = enabledRoles.stream().flatMap(role -> role.getMenus().stream())
            .filter(menu -> Boolean.TRUE.equals(menu.getEnabled())).map(Menu::getPermission)
            .filter(permission -> permission != null && !permission.isBlank()).collect(Collectors.toSet());
        return new AuthUser(user.getId(), user.getUsername(), roles, permissions, type, credentialId, credentialName);
    }
}
