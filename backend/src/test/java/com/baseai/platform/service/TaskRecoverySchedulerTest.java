package com.baseai.platform.service;

import com.baseai.platform.trace.TraceRuntimeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRecoverySchedulerTest {
    /** 活跃任务应通过一条批量 SQL 刷新心跳，超时 SQL 应过滤空心跳。 */
    @Test
    void refreshesActiveHeartbeatsInBatchAndRecoversStaleTraces() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        TraceRuntimeRegistry registry = new TraceRuntimeRegistry();
        registry.create("trace-a");
        registry.create("trace-b");
        TaskRecoveryScheduler scheduler = new TaskRecoveryScheduler(jdbcTemplate, 120, registry);

        scheduler.recoverStaleTraces();

        assertEquals(3, jdbcTemplate.sqlStatements.size());
        assertTrue(jdbcTemplate.sqlStatements.get(0).contains("trace_id IN (?,?)"));
        assertEquals(3, jdbcTemplate.parameters.get(0).length);
        assertTrue(jdbcTemplate.sqlStatements.get(1).contains("heartbeat_at IS NOT NULL"));
        assertTrue(jdbcTemplate.sqlStatements.get(2).contains("heartbeat_at IS NOT NULL"));
    }

    /** 没有活跃任务时不应发送空 IN 条件，但仍应执行超时恢复。 */
    @Test
    void skipsEmptyHeartbeatBatch() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        TaskRecoveryScheduler scheduler = new TaskRecoveryScheduler(jdbcTemplate, 120, new TraceRuntimeRegistry());

        scheduler.recoverStaleTraces();

        assertEquals(2, jdbcTemplate.sqlStatements.size());
        assertTrue(jdbcTemplate.sqlStatements.stream().noneMatch(sql -> sql.contains("IN ()")));
    }

    /** 模拟 JdbcTemplate，记录 SQL 和参数以验证批量更新行为。 */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> sqlStatements = new ArrayList<>();
        private final List<Object[]> parameters = new ArrayList<>();

        /** 记录每次更新调用，并模拟无实际数据库结果。 */
        @Override
        public int update(String sql, Object... args) {
            sqlStatements.add(sql);
            parameters.add(args);
            return 0;
        }
    }
}
