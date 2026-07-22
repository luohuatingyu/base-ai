package com.baseai.platform.logging;

import java.time.Instant;

public record TraceLogRecord(String traceId, String pythonTraceId, String source, String level,
                           String loggerName, String message, String threadName,
                           String throwable, Instant loggedAt) {}
