package com.baseai.platform.trace;

public record TraceContext(String traceId, Long ownerUserId, String taskType, String triggerEntry,
                         TraceCancellationToken cancellationToken, TraceRuntime runtime) {
    public void checkpoint() { cancellationToken.checkpoint(); }
}
