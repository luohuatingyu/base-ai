package com.baseai.platform.security;

import java.util.Set;

public final class DataScopeContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private DataScopeContext() {}

    public static Scope current() { return CURRENT.get(); }
    public static void set(Scope scope) { CURRENT.set(scope); }
    public static void clear() { CURRENT.remove(); }

    public record Scope(boolean all, boolean selfOnly, Long userId, Set<Long> departmentIds) {}
}
