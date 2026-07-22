package com.baseai.platform.controller;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.logging.TraceLogQueue;
import com.baseai.platform.logging.TraceLogRecord;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/internal/trace-logs")
public class InternalTraceLogController {
    private static final Set<String> LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR");
    private final PlatformProperties properties;

    public InternalTraceLogController(PlatformProperties properties) { this.properties = properties; }

    /** 校验内部令牌并接收 Python 批量链路日志。 */
    @PostMapping
    public Map<String, Integer> ingest(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                       @RequestBody LogBatch batch) {
        verifyToken(token);
        int accepted = 0;
        for (LogItem item : batch.logs() == null ? List.<LogItem>of() : batch.logs()) {
            String level = item.level().toUpperCase(Locale.ROOT);
            if (!LEVELS.contains(level)) throw new BusinessException("日志级别无效");
            if (TraceLogQueue.offer(new TraceLogRecord(item.traceId(), item.pythonTraceId(), "PYTHON", level,
                item.loggerName(), item.message(), item.threadName(), item.throwable(),
                item.loggedAt() == null ? Instant.now() : item.loggedAt()))) accepted++;
        }
        return Map.of("accepted", accepted);
    }

    /** 使用常量时间比较内部共享令牌。 */
    private void verifyToken(String token) {
        String expected = properties.getPythonWorker().getInternalToken();
        if (token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw BusinessException.unauthorized("内部令牌无效");
        }
    }

    public record LogBatch(List<LogItem> logs) {}
    public record LogItem(String traceId, String pythonTraceId, String level, String loggerName,
                          String message, String threadName, String throwable, Instant loggedAt) {}
}
