package com.baseai.platform.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TraceContextHolderTest {

    /** 清除当前线程 MDC，避免测试间共享日志上下文。 */
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** 嵌套任务上下文应传播 Trace ID，并在作用域结束后逐层恢复。 */
    @Test
    void bindsAndRestoresTraceId() {
        MDC.put("traceId", "request-trace");
        TraceContext outer = context("outer-trace");
        TraceContext inner = context("inner-trace");

        try (TraceContextHolder.Scope ignored = TraceContextHolder.bind(outer)) {
            assertEquals("outer-trace", TraceContextHolder.currentTraceId().orElseThrow());
            assertEquals("outer-trace", MDC.get("traceId"));
            try (TraceContextHolder.Scope nested = TraceContextHolder.bind(inner)) {
                assertEquals("inner-trace", TraceContextHolder.currentTraceId().orElseThrow());
                assertEquals("inner-trace", MDC.get("traceId"));
            }
            assertEquals("outer-trace", TraceContextHolder.currentTraceId().orElseThrow());
            assertEquals("outer-trace", MDC.get("traceId"));
        }

        assertFalse(TraceContextHolder.current().isPresent());
        assertEquals("request-trace", MDC.get("traceId"));
    }

    /** 创建具有独立运行时和取消令牌的测试任务上下文。 */
    private TraceContext context(String traceId) {
        TraceRuntime runtime = new TraceRuntime(traceId);
        return new TraceContext(traceId, 1L, "测试任务", "TEST", runtime.token(), runtime);
    }
}
