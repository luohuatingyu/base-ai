package com.baseai.platform.logging;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.repository.OperationLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationLogRetentionJobTest {
    /** 清理任务必须按配置天数精确传递截止时间。 */
    @Test
    void deletesOnlyRecordsOlderThanConfiguredRetention() {
        OperationLogRepository repository = mock(OperationLogRepository.class);
        when(repository.deleteByOperatedAtBefore(org.mockito.ArgumentMatchers.any())).thenReturn(3L);
        PlatformProperties properties = new PlatformProperties();
        properties.getAudit().setOperationLogRetentionDays(45);
        Instant now = Instant.parse("2026-09-02T12:00:00Z");

        new OperationLogRetentionJob(repository, properties).cleanup(now);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByOperatedAtBefore(cutoff.capture());
        assertEquals(Instant.parse("2026-07-19T12:00:00Z"), cutoff.getValue());
    }
}
