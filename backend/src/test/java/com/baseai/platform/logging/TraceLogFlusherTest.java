package com.baseai.platform.logging;

import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TraceLogFlusherTest {
    private final List<TraceLogRecord> drained = new ArrayList<>();
    private JdbcTemplate jdbcTemplate;
    private TraceLogFlusher flusher;

    /** 每个用例使用空日志队列和独立数据库模板。 */
    @BeforeEach
    void setUp() {
        TraceLogQueue.drainTo(drained, Integer.MAX_VALUE);
        drained.clear();
        jdbcTemplate = mock(JdbcTemplate.class);
        flusher = new TraceLogFlusher(jdbcTemplate, new PlatformProperties());
    }

    /** 清理静态日志队列，避免影响其他日志测试。 */
    @AfterEach
    void tearDown() {
        TraceLogQueue.drainTo(drained, Integer.MAX_VALUE);
    }

    /** 批量写入必须固定使用当前 level 列且不再探测数据库结构。 */
    @Test
    void flushUsesCurrentLevelColumn() {
        TraceLogQueue.offer(new TraceLogRecord("trace-1", null, "JAVA", "INFO", "logger",
            "message", "thread", null, Instant.now()));

        flusher.flush();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), anyList(), anyInt(),
            any(ParameterizedPreparedStatementSetter.class));
        assertTrue(sql.getValue().contains("source, level, logger_name"));
    }
}
