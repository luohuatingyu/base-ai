package com.baseai.platform.automation;

import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ApiTriggerServiceResponseDecodingTest {
    private final ApiTriggerService service = new ApiTriggerService(mock(JdbcTemplate.class), new ObjectMapper(),
        mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), new PlatformProperties());

    /** JSON 未声明字符集时应按 UTF-8 解码中文响应。 */
    @Test
    void decodesJsonWithoutCharsetAsUtf8() {
        byte[] body = "{\"message\":\"操作成功\"}".getBytes(StandardCharsets.UTF_8);

        String decoded = ReflectionTestUtils.invokeMethod(service, "decodeResponseBody", body, MediaType.APPLICATION_JSON);

        assertEquals("{\"message\":\"操作成功\"}", decoded);
    }

    /** 上游显式声明字符集时应按声明解码响应。 */
    @Test
    void decodesResponseUsingDeclaredCharset() {
        byte[] body = "操作成功".getBytes(StandardCharsets.UTF_16LE);
        MediaType contentType = new MediaType("text", "plain", StandardCharsets.UTF_16LE);

        String decoded = ReflectionTestUtils.invokeMethod(service, "decodeResponseBody", body, contentType);

        assertEquals("操作成功", decoded);
    }

    /** 执行日志应在当前配置范围内按精确 Trace ID 查询。 */
    @Test
    void filtersExecutionLogsByExactTraceIdWithinConfiguration() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApiTriggerService service = spy(new ApiTriggerService(jdbcTemplate, new ObjectMapper(),
            mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), new PlatformProperties()));
        doReturn(mock(ApiTriggerModels.View.class)).when(service).get(9L);
        ApiTriggerModels.LogView matchingLog = mock(ApiTriggerModels.LogView.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(matchingLog));

        List<ApiTriggerModels.LogView> result = service.logs(9L, " trace-1 ");

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertTrue(sql.getValue().contains("config_id=?"));
        assertTrue(sql.getValue().contains("trace_id=?"));
        assertArrayEquals(new Object[]{9L, "trace-1"}, args.getValue());
        assertEquals(List.of(matchingLog), result);
    }

    /** Trace ID 在当前配置中无匹配日志时应返回空列表。 */
    @Test
    void returnsEmptyExecutionLogsWhenTraceIdDoesNotMatch() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApiTriggerService service = spy(new ApiTriggerService(jdbcTemplate, new ObjectMapper(),
            mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), new PlatformProperties()));
        doReturn(mock(ApiTriggerModels.View.class)).when(service).get(9L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        List<ApiTriggerModels.LogView> result = service.logs(9L, "missing-trace");

        assertTrue(result.isEmpty());
    }

    /** 未提供 Trace ID 时执行日志应保持原有最近二百条查询行为。 */
    @Test
    void listsRecentExecutionLogsWithoutTraceIdFilter() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApiTriggerService service = spy(new ApiTriggerService(jdbcTemplate, new ObjectMapper(),
            mock(ConfigCryptoService.class), mock(ApiTriggerUrlPolicy.class), new PlatformProperties()));
        doReturn(mock(ApiTriggerModels.View.class)).when(service).get(9L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        service.logs(9L, " ");

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertFalse(sql.getValue().contains("trace_id=?"));
        assertArrayEquals(new Object[]{9L}, args.getValue());
    }
}
