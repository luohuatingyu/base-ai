package com.baseai.platform.web;

import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.trace.TraceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 异步写入兜底 HTTP 请求任务，避免任务数据库操作阻塞接口响应。 */
@Component
public class HttpRequestTraceAsyncWriter {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestTraceAsyncWriter.class);
    private final TaskTraceService taskTraceService;

    /** 注入任务服务。 */
    public HttpRequestTraceAsyncWriter(TaskTraceService taskTraceService) {
        this.taskTraceService = taskTraceService;
    }

    /** 按创建、成功或失败的顺序异步写入 HTTP 任务。 */
    @Async("auditTaskExecutor")
    public void write(String traceId, Long ownerUserId, String requestMethod, String requestPath,
                      TraceSnapshot snapshot, boolean failed, String failureMessage) {
        try {
            String createdTraceId = taskTraceService.create(traceId, ownerUserId, "HTTP Request", "API",
                requestMethod, requestPath, snapshot);
            if (failed) taskTraceService.markFailed(createdTraceId, failureMessage);
            else taskTraceService.markSuccess(createdTraceId);
        } catch (RuntimeException exception) {
            log.warn("event=http_trace_async_write_failed trace_id={} error_type={}",
                traceId, exception.getClass().getSimpleName());
        }
    }
}
