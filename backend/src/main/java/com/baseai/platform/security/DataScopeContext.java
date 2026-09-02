package com.baseai.platform.security;

import java.util.Set;

public final class DataScopeContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private DataScopeContext() {}

    public static Scope current() { return CURRENT.get(); }
    public static void set(Scope scope) { CURRENT.set(scope); }
    public static void clear() { CURRENT.remove(); }

    /** 聚合当前用户可读取的全部范围；self 表示“仅本人”角色贡献的可见性。 */
    public record Scope(boolean all, boolean self, Long userId, Set<Long> departmentIds) {}
}
