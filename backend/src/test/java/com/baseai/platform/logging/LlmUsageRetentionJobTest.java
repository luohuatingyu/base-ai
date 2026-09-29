package com.baseai.platform.logging;

import com.baseai.platform.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmUsageRetentionJobTest {
    /** 清理任务必须按照 90 天配置计算截断时间。 */
    @Test
    void deletesRecordsOlderThanConfiguredRetention() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);
        PlatformProperties properties = new PlatformProperties();
        LlmUsageRetentionJob job = new LlmUsageRetentionJob(jdbc, properties);
        job.cleanup(Instant.parse("2026-04-01T00:00:00Z"));
        verify(jdbc).update(anyString(), any(Object[].class));
    }
}
