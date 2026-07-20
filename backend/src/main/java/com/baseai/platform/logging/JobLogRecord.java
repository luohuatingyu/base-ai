package com.baseai.platform.logging;

import java.time.Instant;

public record JobLogRecord(String jobId, String pythonJobId, String source, String level,
                           String loggerName, String message, String threadName,
                           String throwable, Instant loggedAt) {}
