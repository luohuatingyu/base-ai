package com.baseai.platform.service;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.trace.TaskTypeRegistry;
import com.baseai.platform.trace.TraceRuntimeRegistry;
import com.baseai.platform.trace.TraceSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskTraceServiceTraceIdTest {
    /** 任务创建应复用请求入口生成的 traceId，而不是再次生成。 */
    @Test
    void createReusesBackendRequestTraceId() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TaskTypeRegistry taskTypeRegistry = mock(TaskTypeRegistry.class);
        TaskTraceService service = new TaskTraceService(jdbcTemplate, mock(TraceRuntimeRegistry.class),
            mock(RestClient.class), new PlatformProperties(), taskTypeRegistry);

        String traceId = service.create("backend-generated-trace", 7L, "USER_CREATE", "API", "POST",
            "/api/system/users", new TraceSnapshot("{}", "{}"));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), arguments.capture());
        assertEquals("backend-generated-trace", traceId);
        assertEquals("backend-generated-trace", arguments.getValue()[0]);
        verify(taskTypeRegistry).register("USER_CREATE", "API");
    }

    /** 任务列表应按精确 Trace ID 查询，并与状态及用户数据范围组合生效。 */
    @Test
    void tracesFilterByExactTraceIdWithinOwnerScope() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TaskTraceService service = new TaskTraceService(jdbcTemplate, mock(TraceRuntimeRegistry.class),
            mock(RestClient.class), new PlatformProperties(), mock(TaskTypeRegistry.class));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("trace_id", "trace-1")));

        Map<String, Object> result = service.traces(7L, false, " trace-1 ", "SUCCESS", null, null,
            null, false, null, null, 1, 20);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Integer.class), countArgs.capture());
        assertTrue(countSql.getValue().contains("t.owner_user_id=?"));
        assertTrue(countSql.getValue().contains("t.trace_id=?"));
        assertArrayEquals(new Object[]{7L, "trace-1", "SUCCESS"}, countArgs.getValue());

        ArgumentCaptor<String> listSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> listArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(listSql.capture(), listArgs.capture());
        assertTrue(listSql.getValue().contains("t.owner_user_id=?"));
        assertTrue(listSql.getValue().contains("t.trace_id=?"));
        assertArrayEquals(new Object[]{7L, "trace-1", "SUCCESS", 20, 0}, listArgs.getValue());
        assertEquals(1, result.get("total"));
    }

    /** Trace ID 无匹配任务时列表与分页总数应同时为空。 */
    @Test
    void tracesReturnEmptyPageWhenTraceIdDoesNotMatch() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TaskTraceService service = new TaskTraceService(jdbcTemplate, mock(TraceRuntimeRegistry.class),
            mock(RestClient.class), new PlatformProperties(), mock(TaskTypeRegistry.class));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> result = service.traces(7L, false, "missing-trace", null, null, null,
            null, false, null, null, 2, 10);

        assertEquals(List.of(), result.get("records"));
        assertEquals(0, result.get("total"));
        assertEquals(2, result.get("page"));
        assertEquals(10, result.get("pageSize"));
    }

    /** 未传 Trace ID 时应保持原有任务列表查询条件。 */
    @Test
    void tracesKeepExistingBehaviorWithoutTraceId() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TaskTraceService service = new TaskTraceService(jdbcTemplate, mock(TraceRuntimeRegistry.class),
            mock(RestClient.class), new PlatformProperties(), mock(TaskTypeRegistry.class));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.traces(7L, false, " ", null, null, null, null, false, null, null, 1, 20);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Integer.class), any(Object[].class));
        assertFalse(countSql.getValue().contains("t.trace_id=?"));
    }
}
