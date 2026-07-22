package com.baseai.platform.trace;

import org.slf4j.MDC;

import java.util.Optional;

public final class TraceContextHolder {
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();
    private TraceContextHolder() {}

    public static Optional<TraceContext> current() { return Optional.ofNullable(CURRENT.get()); }
    public static Optional<String> currentTraceId() { return current().map(TraceContext::traceId); }
    public static void checkpoint() { current().ifPresent(TraceContext::checkpoint); }

    /** 绑定任务上下文并在关闭作用域时恢复原状态。 */
    public static Scope bind(TraceContext context) {
        TraceContext previous = CURRENT.get();
        String previousTraceId = MDC.get("traceId");
        CURRENT.set(context);
        MDC.put("traceId", context.traceId());
        return new Scope(previous, previousTraceId);
    }

    public static final class Scope implements AutoCloseable {
        private final TraceContext previous;
        private final String previousTraceId;
        private Scope(TraceContext previous, String previousTraceId) { this.previous = previous; this.previousTraceId = previousTraceId; }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            if (previousTraceId == null) MDC.remove("traceId"); else MDC.put("traceId", previousTraceId);
        }
    }
}
