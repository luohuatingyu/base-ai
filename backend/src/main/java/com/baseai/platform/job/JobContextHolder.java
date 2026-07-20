package com.baseai.platform.job;

import org.slf4j.MDC;

import java.util.Optional;

public final class JobContextHolder {
    private static final ThreadLocal<JobContext> CURRENT = new ThreadLocal<>();
    private JobContextHolder() {}

    public static Optional<JobContext> current() { return Optional.ofNullable(CURRENT.get()); }
    public static Optional<String> currentJobId() { return current().map(JobContext::jobId); }
    public static void checkpoint() { current().ifPresent(JobContext::checkpoint); }

    /** 绑定任务上下文并在关闭作用域时恢复原状态。 */
    public static Scope bind(JobContext context) {
        JobContext previous = CURRENT.get();
        String previousJobId = MDC.get("jobId");
        CURRENT.set(context);
        MDC.put("jobId", context.jobId());
        return new Scope(previous, previousJobId);
    }

    public static final class Scope implements AutoCloseable {
        private final JobContext previous;
        private final String previousJobId;
        private Scope(JobContext previous, String previousJobId) { this.previous = previous; this.previousJobId = previousJobId; }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            if (previousJobId == null) MDC.remove("jobId"); else MDC.put("jobId", previousJobId);
        }
    }
}
