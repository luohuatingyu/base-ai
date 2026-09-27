package com.baseai.platform.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmUsageServiceTest {
    /** 使用记录必须包含来源、链路和用户范围，且数据库写入失败不能阻断调用方。 */
    @Test
    void startsUsageWithoutLeakingContent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LlmUsageService service = new LlmUsageService(jdbc);
        String id = service.start(new LlmUsageService.Start("trace-1", "python-1", 7L, 9L,
            "STREAM", "chat", "text_model", Instant.parse("2026-01-01T00:00:00Z")));

        assertEquals(32, id.length());
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertEquals("trace-1", args.getValue()[1]);
        assertEquals("STREAM", args.getValue()[5]);
        assertEquals("chat", args.getValue()[6]);
    }

    /** 查询窗口必须严格递增且不能超过配置的 90 天监控上限。 */
    @Test
    void rejectsInvalidQueryRange() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () ->
            new LlmUsageService.Query(start, start, null, null, null, null).validated());
        assertThrows(IllegalArgumentException.class, () ->
            new LlmUsageService.Query(start, start.plusSeconds(91 * 86400L), null, null, null, null).validated());
    }

    /** 终态更新必须写入 Token 和错误元数据，而不包含模型正文。 */
    @Test
    void finishesUsageWithMetricsOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LlmUsageService service = new LlmUsageService(jdbc);
        service.finish("usage-1", new LlmUsageService.Completion("FAILED", "provider", "model", false,
            null, null, null, "TimeoutException", "ai.serviceCallFailed", Instant.parse("2026-01-01T00:00:01Z")));
        verify(jdbc).update(anyString(), any(Object[].class));
    }
}
