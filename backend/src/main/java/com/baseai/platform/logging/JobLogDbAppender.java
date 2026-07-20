package com.baseai.platform.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;

public class JobLogDbAppender extends AppenderBase<ILoggingEvent> {
    private int capacity = 10000;
    private String persistLevel = "INFO";
    private Level threshold = Level.INFO;

    /** 初始化日志队列和持久化级别。 */
    @Override
    public void start() {
        JobLogQueue.configure(capacity);
        threshold = Level.toLevel(persistLevel, Level.INFO);
        super.start();
    }

    /** 将带任务编号的日志非阻塞写入统一队列。 */
    @Override
    protected void append(ILoggingEvent event) {
        String jobId = event.getMDCPropertyMap().get("jobId");
        if (jobId == null || jobId.isBlank() || !event.getLevel().isGreaterOrEqual(threshold)) return;
        String throwable = event.getThrowableProxy() == null ? null : ThrowableProxyUtil.asString(event.getThrowableProxy());
        JobLogQueue.offer(new JobLogRecord(jobId, null, "JAVA", event.getLevel().levelStr,
            event.getLoggerName(), event.getFormattedMessage(), event.getThreadName(), throwable,
            Instant.ofEpochMilli(event.getTimeStamp())));
    }

    public void setCapacity(int capacity) { this.capacity = capacity; }
    public void setPersistLevel(String persistLevel) { this.persistLevel = persistLevel; }
}
