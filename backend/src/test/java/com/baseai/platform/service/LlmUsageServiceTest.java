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

    /** 首 Token 时间使用同一 Java 时间戳绑定两次，避免数据库时区差异产生负延迟。 */
    @Test
    void recordsFirstTokenWithBoundTimestampAndClampsNegativeLatency() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LlmUsageService service = new LlmUsageService(jdbc);
        Instant firstTokenAt = Instant.parse("2026-01-01T00:00:00.120Z");

        service.markFirstToken("usage-1", firstTokenAt);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("COALESCE(first_token_at, ?),"), sql.getValue());
        assertTrue(sql.getValue().contains("GREATEST(TIMESTAMPDIFF(MICROSECOND, started_at, ?) DIV 1000, 0)"), sql.getValue());
        assertEquals(args.getValue()[0], args.getValue()[1]);
        assertEquals("usage-1", args.getValue()[2]);
    }

    /** 动态查询条件必须和 WHERE 关键字分开，避免监控接口生成非法 SQL。 */
    @Test
    void summarySeparatesWhereKeywordFromFilters() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(java.util.Map.of());
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        LlmUsageService service = new LlmUsageService(jdbc);

        service.summary(LlmUsageService.Query.last24Hours());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForMap(sql.capture(), any(Object[].class));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("WHERE started_at"), sql.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("AVG(CASE WHEN first_token_latency_ms >= 0 THEN first_token_latency_ms END)"), sql.getValue());
    }
}
