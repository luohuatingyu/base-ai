package com.baseai.platform.trace;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TraceCancellationToken {
    private final String traceId;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public TraceCancellationToken(String traceId) { this.traceId = traceId; }
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }

    /** 在业务边界检查取消和线程中断状态。 */
    public void checkpoint() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new TraceCancelledException(traceId);
    }
}
