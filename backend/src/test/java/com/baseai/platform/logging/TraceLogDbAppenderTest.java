package com.baseai.platform.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceLogDbAppenderTest {
    private final List<TraceLogRecord> drained = new ArrayList<>();
    private TraceLogDbAppender appender;

    /** 每个用例使用空队列和 INFO 持久化阈值。 */
    @BeforeEach
    void setUp() {
        TraceLogQueue.drainTo(drained, Integer.MAX_VALUE);
        drained.clear();
        appender = new TraceLogDbAppender();
        appender.setPersistLevel("INFO");
        appender.start();
    }

    /** 停止 Appender 并清理静态日志队列。 */
    @AfterEach
    void tearDown() {
        appender.stop();
        TraceLogQueue.drainTo(drained, Integer.MAX_VALUE);
    }

    /** 仅持久化达到阈值且携带 Trace ID 的日志事件。 */
    @Test
    void persistsOnlyTraceAwareEventsAtThreshold() {
        appender.doAppend(event(Level.INFO, Map.of("traceId", "trace-1"), "accepted"));
        appender.doAppend(event(Level.DEBUG, Map.of("traceId", "trace-2"), "below-threshold"));
        appender.doAppend(event(Level.ERROR, Map.of(), "missing-trace"));

        assertEquals(1, TraceLogQueue.drainTo(drained, 10));
        TraceLogRecord record = drained.get(0);
        assertEquals("trace-1", record.traceId());
        assertEquals("accepted", record.message());
        assertEquals("JAVA", record.source());
    }

    /** 构造带指定 MDC 的 Logback 测试事件。 */
    private LoggingEvent event(Level level, Map<String, String> mdc, String message) {
        Logger logger = (Logger) LoggerFactory.getLogger("trace-test");
        LoggingEvent event = new LoggingEvent(getClass().getName(), logger, level, message, null, null);
        event.setMDCPropertyMap(mdc);
        return event;
    }
}
