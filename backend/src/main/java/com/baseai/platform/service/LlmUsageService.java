package com.baseai.platform.service;

import com.baseai.platform.security.AuthContext;
import com.baseai.platform.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 记录模型调用明细并为管理端提供聚合查询。记录失败时不影响模型主流程。 */
@Service
public class LlmUsageService {
    private static final Logger log = LoggerFactory.getLogger(LlmUsageService.class);
    private final JdbcTemplate jdbcTemplate;

    /** 注入业务数据库查询模板。 */
    public LlmUsageService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 登记一次实际模型调用，返回后续更新使用的内部调用标识。 */
    public String start(Start start) {
        String usageId = UUID.randomUUID().toString().replace("-", "");
        Instant startedAt = start.startedAt() == null ? Instant.now() : start.startedAt();
        try {
            jdbcTemplate.update("""
                INSERT INTO llm_usage_record(usage_id, trace_id, python_trace_id, owner_user_id, conversation_id,
                    source, feature_code, model_type, status, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                """, usageId, start.traceId(), start.pythonTraceId(), resolveOwner(start.ownerUserId(), start.traceId()),
                start.conversationId(), normalize(start.source(), "UNKNOWN", 32), normalize(start.featureCode(), null, 100),
                normalize(start.modelType(), null, 100), Timestamp.from(startedAt));
        } catch (RuntimeException exception) {
            log.warn("event=llm_usage_start_failed source={} error_type={}", start.source(), exception.getClass().getSimpleName());
            return null;
        }
        return usageId;
    }

    /** 登记流式调用已经产生首个可见增量，重复事件不会覆盖首次延迟。 */
    public void markFirstToken(String usageId) {
        if (usageId == null) return;
        try {
            jdbcTemplate.update("""
                UPDATE llm_usage_record
                SET first_token_at=COALESCE(first_token_at, CURRENT_TIMESTAMP(6)),
                    first_token_latency_ms=COALESCE(first_token_latency_ms,
                        TIMESTAMPDIFF(MICROSECOND, started_at, CURRENT_TIMESTAMP(6)) DIV 1000),
                    has_output=b'1'
                WHERE usage_id=? AND first_token_at IS NULL
                """, usageId);
        } catch (RuntimeException exception) {
            log.warn("event=llm_usage_first_token_failed error_type={}", exception.getClass().getSimpleName());
        }
    }

    /** 记录模型调用终态和供应商返回的 Token 指标。 */
    public void finish(String usageId, Completion completion) {
        if (usageId == null) return;
        Instant finishedAt = completion.finishedAt() == null ? Instant.now() : completion.finishedAt();
        try {
            jdbcTemplate.update("""
                UPDATE llm_usage_record SET provider_code=?, model_name=?, status=?, has_output=?,
                    input_tokens=?, output_tokens=?, total_tokens=?, finished_at=?,
                    duration_ms=TIMESTAMPDIFF(MICROSECOND, started_at, ? ) DIV 1000,
                    error_type=?, error_code=? WHERE usage_id=?
                """, normalize(completion.providerCode(), null, 64), normalize(completion.modelName(), null, 160),
                normalize(completion.status(), "FAILED", 24), Boolean.TRUE.equals(completion.hasOutput()),
                completion.inputTokens(), completion.outputTokens(), completion.totalTokens(), Timestamp.from(finishedAt),
                Timestamp.from(finishedAt), normalize(completion.errorType(), null, 100), normalize(completion.errorCode(), null, 100), usageId);
        } catch (RuntimeException exception) {
            log.warn("event=llm_usage_finish_failed error_type={}", exception.getClass().getSimpleName());
        }
    }

    /** 返回管理端的时间窗口汇总、趋势、模型排行和错误排行。 */
    public Map<String, Object> summary(Query query) {
        List<Object> args = new ArrayList<>();
        String where = filters(query, args);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", query.from());
        result.put("to", query.to());
        result.put("overview", jdbcTemplate.queryForMap("""
            SELECT COUNT(*) calls,
                SUM(status='SUCCESS') success,
                SUM(status='FAILED') failed,
                SUM(status='INTERRUPTED') interrupted,
                SUM(status='CANCELLED') cancelled,
                SUM(status='RUNNING') running,
                COALESCE(SUM(input_tokens), 0) inputTokens,
                COALESCE(SUM(output_tokens), 0) outputTokens,
                COALESCE(SUM(total_tokens), 0) totalTokens,
                AVG(duration_ms) averageDurationMs,
                AVG(first_token_latency_ms) averageFirstTokenLatencyMs,
                SUM(input_tokens IS NOT NULL) tokenRecords
            FROM llm_usage_record WHERE """ + where, args.toArray()));

        List<Object> trendArgs = new ArrayList<>();
        String trendWhere = filters(query, trendArgs);
        result.put("trend", jdbcTemplate.queryForList("""
            SELECT DATE_FORMAT(started_at, '%Y-%m-%d %H:00:00') bucket,
                COUNT(*) calls, SUM(status='SUCCESS') success, SUM(status IN ('FAILED','INTERRUPTED','CANCELLED')) failures,
                COALESCE(SUM(total_tokens), 0) totalTokens
            FROM llm_usage_record WHERE """ + trendWhere + " GROUP BY bucket ORDER BY bucket", trendArgs.toArray()));

        List<Object> dimensionArgs = new ArrayList<>();
        String dimensionWhere = filters(query, dimensionArgs);
        result.put("models", jdbcTemplate.queryForList("""
            SELECT COALESCE(provider_code, 'unknown') providerCode, COALESCE(model_name, 'unknown') modelName,
                COUNT(*) calls, SUM(status='SUCCESS') success, SUM(status IN ('FAILED','INTERRUPTED','CANCELLED')) failures,
                COALESCE(SUM(total_tokens), 0) totalTokens, AVG(duration_ms) averageDurationMs
            FROM llm_usage_record WHERE """ + dimensionWhere
            + " GROUP BY provider_code, model_name ORDER BY calls DESC LIMIT 20", dimensionArgs.toArray()));

        List<Object> errorArgs = new ArrayList<>();
        String errorWhere = filters(query, errorArgs);
        result.put("errors", jdbcTemplate.queryForList("""
            SELECT COALESCE(error_type, 'unknown') errorType, COALESCE(error_code, 'unknown') errorCode,
                COUNT(*) count
            FROM llm_usage_record WHERE """ + errorWhere + " AND status IN ('FAILED','INTERRUPTED','CANCELLED')"
            + " GROUP BY error_type, error_code ORDER BY count DESC LIMIT 20", errorArgs.toArray()));
        return result;
    }

    /** 返回分页模型使用明细，管理端仅能通过受保护控制器访问。 */
    public Page records(Query query, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        List<Object> args = new ArrayList<>();
        String where = filters(query, args);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_usage_record WHERE " + where, Long.class, args.toArray());
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add((safePage - 1) * safeSize);
        listArgs.add(safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
            SELECT usage_id usageId, trace_id traceId, python_trace_id pythonTraceId, owner_user_id ownerUserId,
                conversation_id conversationId, source, feature_code featureCode, model_type modelType,
                provider_code providerCode, model_name modelName, status, has_output hasOutput,
                input_tokens inputTokens, output_tokens outputTokens, total_tokens totalTokens,
                started_at startedAt, first_token_at firstTokenAt, finished_at finishedAt,
                duration_ms durationMs, first_token_latency_ms firstTokenLatencyMs, error_type errorType, error_code errorCode
            FROM llm_usage_record WHERE """ + where + " ORDER BY started_at DESC LIMIT ?, ?", listArgs.toArray());
        return new Page(records, total == null ? 0L : total, safePage, safeSize);
    }

    /** 组装带参数的过滤条件，列名只来自固定白名单。 */
    private String filters(Query query, List<Object> args) {
        StringBuilder sql = new StringBuilder("started_at >= ? AND started_at < ?");
        args.add(Timestamp.from(query.from()));
        args.add(Timestamp.from(query.to()));
        if (query.providerCode() != null && !query.providerCode().isBlank()) { sql.append(" AND provider_code=?"); args.add(query.providerCode().trim()); }
        if (query.modelName() != null && !query.modelName().isBlank()) { sql.append(" AND model_name=?"); args.add(query.modelName().trim()); }
        if (query.source() != null && !query.source().isBlank()) { sql.append(" AND source=?"); args.add(query.source().trim()); }
        if (query.status() != null && !query.status().isBlank()) { sql.append(" AND status=?"); args.add(query.status().trim().toUpperCase()); }
        return sql.toString();
    }

    /** 查询调用所属用户；异步工作流线程没有登录上下文时通过父 Trace 补齐。 */
    private Long resolveOwner(Long ownerUserId, String traceId) {
        if (ownerUserId != null) return ownerUserId;
        if (AuthContext.current() != null) return AuthContext.current().id();
        if (traceId == null || traceId.isBlank()) return null;
        try { return jdbcTemplate.queryForObject("SELECT owner_user_id FROM task_trace WHERE trace_id=?", Long.class, traceId); }
        catch (RuntimeException ignored) { return null; }
    }

    /** 统一限制记录字段长度，避免异常数据污染监控表。 */
    private String normalize(String value, String fallback, int maxLength) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    /** 模型调用开始信息。 */
    public record Start(String traceId, String pythonTraceId, Long ownerUserId, Long conversationId,
                        String source, String featureCode, String modelType, Instant startedAt) {
        /** 使用当前链路 Trace 构造开始信息。 */
        public static Start current(String pythonTraceId, Long ownerUserId, Long conversationId,
                                    String source, String featureCode, String modelType) {
            return new Start(TraceContextHolder.currentTraceId().orElse(null), pythonTraceId, ownerUserId,
                conversationId, source, featureCode, modelType, Instant.now());
        }
    }

    /** 模型调用终态指标。 */
    public record Completion(String status, String providerCode, String modelName, Boolean hasOutput,
                             Long inputTokens, Long outputTokens, Long totalTokens,
                             String errorType, String errorCode, Instant finishedAt) {}

    /** 管理端聚合查询条件。 */
    public record Query(Instant from, Instant to, String providerCode, String modelName, String source, String status) {
        /** 创建最近 24 小时的默认查询。 */
        public static Query last24Hours() {
            Instant to = Instant.now();
            return new Query(to.minus(24, ChronoUnit.HOURS), to, null, null, null, null);
        }
        /** 校验时间窗口和状态，避免查询过大的历史范围。 */
        public Query validated() {
            if (from == null || to == null || !from.isBefore(to)) throw new IllegalArgumentException("Invalid usage time range");
            if (Duration.between(from, to).compareTo(Duration.ofDays(90)) > 0) throw new IllegalArgumentException("Usage range exceeds 90 days");
            return new Query(from, to, providerCode, modelName, source, status);
        }
    }

    /** 分页结果。 */
    public record Page(List<Map<String, Object>> records, long total, int page, int pageSize) {}
}
