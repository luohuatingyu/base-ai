package com.baseai.platform.web;

import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.trace.TraceSnapshot;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpRequestTraceAsyncWriterTest {
    /** 异步写入器必须按创建后终态更新的顺序持久化任务。 */
    @Test
    void writesSuccessTaskInOrder() {
        TaskTraceService service = mock(TaskTraceService.class);
        HttpRequestTraceAsyncWriter writer = new HttpRequestTraceAsyncWriter(service);
        TraceSnapshot snapshot = new TraceSnapshot("{}", "{}");
        when(service.create("trace-1", 7L, "HTTP Request", "API", "GET", "/api/test", snapshot))
            .thenReturn("trace-1");

        writer.write("trace-1", 7L, "GET", "/api/test", snapshot, false, null);

        var ordered = inOrder(service);
        ordered.verify(service).create("trace-1", 7L, "HTTP Request", "API", "GET", "/api/test", snapshot);
        ordered.verify(service).markSuccess("trace-1");
    }

    /** 异步写入器收到失败结果时必须写入失败状态和原因。 */
    @Test
    void writesFailedTask() {
        TaskTraceService service = mock(TaskTraceService.class);
        HttpRequestTraceAsyncWriter writer = new HttpRequestTraceAsyncWriter(service);
        TraceSnapshot snapshot = new TraceSnapshot("{}", "{}");
        when(service.create("trace-2", 8L, "HTTP Request", "API", "POST", "/api/test", snapshot))
            .thenReturn("trace-2");

        writer.write("trace-2", 8L, "POST", "/api/test", snapshot, true, "HTTP status 503");

        verify(service).markFailed("trace-2", "HTTP status 503");
    }
}
