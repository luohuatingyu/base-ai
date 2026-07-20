package com.baseai.platform.job;

import java.util.concurrent.atomic.AtomicBoolean;

public final class JobCancellationToken {
    private final String jobId;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public JobCancellationToken(String jobId) { this.jobId = jobId; }
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }

    /** 在业务边界检查取消和线程中断状态。 */
    public void checkpoint() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new JobCancelledException(jobId);
    }
}
