package com.baseai.platform.service;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.trace.TaskTypeRegistry;
import com.baseai.platform.trace.TraceRuntimeRegistry;
import com.baseai.platform.trace.TraceSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
