package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;

public final class AuthContext {
    private static final ThreadLocal<AuthUser> CURRENT = new ThreadLocal<>();
    private AuthContext() {}

    public static void set(AuthUser user) { CURRENT.set(user); }
    public static AuthUser current() { return CURRENT.get(); }
    public static AuthUser require() {
        AuthUser user = CURRENT.get();
        if (user == null) throw BusinessException.unauthorized("请先登录");
        return user;
    }
    public static void clear() { CURRENT.remove(); }
}
