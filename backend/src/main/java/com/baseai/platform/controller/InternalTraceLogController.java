package com.baseai.platform.controller;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.logging.TraceLogQueue;
import com.baseai.platform.logging.TraceLogRecord;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 内部链路日志控制器
 * <p>
 * 该控制器用于接收来自Python Worker的链路日志数据，是一个内部API接口。
 * 主要功能包括：
 * <ul>
 *   <li>验证正文绑定的内部 HMAC 签名，确保只有授权的 Python Worker 可以提交日志</li>
 *   <li>接收批量的Python链路日志数据</li>
 *   <li>将日志数据添加到链路日志队列中进行异步处理</li>
 * </ul>
 *
 * @author Base-AI Platform
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/internal/trace-logs")
public class InternalTraceLogController {
    /**
     * 支持的日志级别集合
     * 包含：DEBUG、INFO、WARN、ERROR
     */
    private static final Set<String> LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR");

    /**
     * 接收并处理来自Python Worker的批量链路日志
     * <p>
     * 该接口用于接收Python Worker提交的链路日志数据，会进行以下处理：
     * <ol>
     *   <li>由统一过滤器验证内部签名并拒绝重放</li>
     *   <li>遍历批量日志数据，验证每条日志的级别</li>
     *   <li>将有效的日志记录添加到链路日志队列中</li>
     *   <li>返回成功接收的日志数量</li>
     * </ol>
     *
     * @param batch 批量日志数据对象，包含多条日志记录
     * @return Map对象，包含"accepted"键，表示成功接收的日志数量
     * @throws BusinessException 当日志级别不合法时抛出
     */
    @PostMapping
    public Map<String, Integer> ingest(@RequestBody LogBatch batch) {
        // 记录成功接收的日志数量
        int accepted = 0;

        // 遍历批量日志数据，如果logs为null则使用空列表
        for (LogItem item : batch.logs() == null ? List.<LogItem>of() : batch.logs()) {
            // 将日志级别转换为大写
            String level = item.level().toUpperCase(Locale.ROOT);

            // 验证日志级别是否合法
            if (!LEVELS.contains(level)) throw new BusinessException("internal.logLevelInvalid");

            // 将日志记录添加到队列中，如果成功则计数加1
            // 如果loggedAt为null则使用当前时间
            if (TraceLogQueue.offer(new TraceLogRecord(item.traceId(), item.pythonTraceId(), "PYTHON", level,
                item.loggerName(), item.message(), item.threadName(), item.throwable(),
                item.loggedAt() == null ? Instant.now() : item.loggedAt()))) accepted++;
        }

        // 返回成功接收的日志数量
        return Map.of("accepted", accepted);
    }

    /**
     * 批量日志数据记录
     * <p>
     * 封装了批量日志提交的请求体结构
     *
     * @param logs 日志条目列表
     */
    public record LogBatch(List<LogItem> logs) {}

    /**
     * 单条日志条目记录
     * <p>
     * 封装了单条日志的所有必要信息
     *
     * @param traceId Java端的链路追踪ID
     * @param pythonTraceId Python端的链路追踪ID
     * @param level 日志级别（DEBUG、INFO、WARN、ERROR）
     * @param loggerName 日志记录器名称
     * @param message 日志消息内容
     * @param threadName 线程名称
     * @param throwable 异常堆栈信息（如果有）
     * @param loggedAt 日志记录时间戳
     */
    public record LogItem(String traceId, String pythonTraceId, String level, String loggerName,
                          String message, String threadName, String throwable, Instant loggedAt) {}
}
