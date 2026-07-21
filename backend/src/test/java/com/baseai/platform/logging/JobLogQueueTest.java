package com.baseai.platform.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class JobLogQueueTest {

    /** 验证任务日志队列满载时不阻塞并记录丢弃数量。 */
    @Test
    void recordsDroppedLogsWhenQueueIsFull() {
        JobLogQueue.configure(100);
        JobLogRecord record = new JobLogRecord("job", null, "JAVA", "INFO", "test", "message", "thread", null, Instant.now());
        for (int index = 0; index < 100; index++) assertThat(JobLogQueue.offer(record)).isTrue();

        assertThat(JobLogQueue.offer(record)).isFalse();
        assertThat(JobLogQueue.drainDroppedCount()).isEqualTo(1);
        ArrayList<JobLogRecord> drained = new ArrayList<>();
        assertThat(JobLogQueue.drainTo(drained, 100)).isEqualTo(100);
    }
}
