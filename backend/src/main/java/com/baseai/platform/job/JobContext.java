package com.baseai.platform.job;

public record JobContext(String jobId, Long ownerUserId, String taskType, String triggerEntry,
                         JobCancellationToken cancellationToken, JobRuntime runtime) {
    public void checkpoint() { cancellationToken.checkpoint(); }
}
