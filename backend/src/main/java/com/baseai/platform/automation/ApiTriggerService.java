package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.job.JobContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ApiTriggerService {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final ApiTriggerUrlPolicy urlPolicy;
    private final int resultMaxLength;

    public ApiTriggerService(@Qualifier("postgresqlJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                             ConfigCryptoService cryptoService, ApiTriggerUrlPolicy urlPolicy, PlatformProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.urlPolicy = urlPolicy;
        this.resultMaxLength = properties.getApiTrigger().getResultMaxLength();
    }

    /** 按关键字和状态查询未作废接口配置。 */
    public List<ApiTriggerModels.View> list(String keyword, Boolean enabled) {
        StringBuilder sql = new StringBuilder("SELECT * FROM automation_api_trigger_config WHERE voided=false");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(name) LIKE LOWER(?) OR LOWER(description) LIKE LOWER(?))");
            args.add("%" + keyword.trim() + "%"); args.add("%" + keyword.trim() + "%");
        }
        if (enabled != null) { sql.append(" AND enabled=?"); args.add(enabled); }
        sql.append(" ORDER BY id DESC");
        return jdbcTemplate.query(sql.toString(), (rs, row) -> mapView(rs), args.toArray());
    }

    /** 创建接口触发配置并加密敏感字段。 */
    public ApiTriggerModels.View create(ApiTriggerModels.Command command, Long ownerUserId) {
        validate(command);
        Long id = jdbcTemplate.queryForObject("""
            INSERT INTO automation_api_trigger_config(name, description, http_method, url, headers_encrypted,
                query_params, request_body_encrypted, content_type, cron_expression, timeout_seconds, enabled,
                auth_enabled, auth_url, auth_method, auth_body_encrypted, auth_content_type, auth_token_path,
                auth_token_header, auth_token_prefix, owner_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, Long.class, text(command.name()), text(command.description()), method(command.httpMethod()), text(command.url()),
            cryptoService.encrypt(text(command.headers())), text(command.queryParams()), cryptoService.encrypt(text(command.requestBody())),
            contentType(command.contentType()), cron(command.cronExpression()), timeout(command.timeoutSeconds()), enabled(command.enabled()),
            Boolean.TRUE.equals(command.authEnabled()), text(command.authUrl()), methodOrDefault(command.authMethod(), "POST"),
            cryptoService.encrypt(text(command.authBody())), contentType(command.authContentType()), defaultText(command.authTokenPath(), "data.token"),
            defaultText(command.authTokenHeader(), "Authorization"), command.authTokenPrefix() == null ? "Bearer " : command.authTokenPrefix(), ownerUserId);
        return get(id);
    }

    /** 更新接口触发配置并重新加密敏感字段。 */
    public ApiTriggerModels.View update(Long id, ApiTriggerModels.Command command) {
        get(id);
        validate(command);
        jdbcTemplate.update("""
            UPDATE automation_api_trigger_config SET name=?, description=?, http_method=?, url=?, headers_encrypted=?,
                query_params=?, request_body_encrypted=?, content_type=?, cron_expression=?, timeout_seconds=?, enabled=?,
                auth_enabled=?, auth_url=?, auth_method=?, auth_body_encrypted=?, auth_content_type=?, auth_token_path=?,
                auth_token_header=?, auth_token_prefix=?, updated_at=NOW() WHERE id=? AND voided=false
            """, text(command.name()), text(command.description()), method(command.httpMethod()), text(command.url()),
            cryptoService.encrypt(text(command.headers())), text(command.queryParams()), cryptoService.encrypt(text(command.requestBody())),
            contentType(command.contentType()), cron(command.cronExpression()), timeout(command.timeoutSeconds()), enabled(command.enabled()),
            Boolean.TRUE.equals(command.authEnabled()), text(command.authUrl()), methodOrDefault(command.authMethod(), "POST"),
            cryptoService.encrypt(text(command.authBody())), contentType(command.authContentType()), defaultText(command.authTokenPath(), "data.token"),
            defaultText(command.authTokenHeader(), "Authorization"), command.authTokenPrefix() == null ? "Bearer " : command.authTokenPrefix(), id);
        return get(id);
    }

    public ApiTriggerModels.View get(Long id) {
        List<ApiTriggerModels.View> rows = jdbcTemplate.query("SELECT * FROM automation_api_trigger_config WHERE id=?",
            (rs, row) -> mapView(rs), id);
        if (rows.isEmpty()) throw BusinessException.notFound("接口触发配置不存在");
        return rows.get(0);
    }

    /** 停用配置并保留历史记录。 */
    public void disable(Long id) {
        if (jdbcTemplate.update("UPDATE automation_api_trigger_config SET enabled=false, updated_at=NOW() WHERE id=? AND voided=false", id) == 0)
            throw BusinessException.notFound("接口触发配置不存在");
    }

    /** 作废配置并从正常列表隐藏。 */
    public void voidConfig(Long id) {
        if (jdbcTemplate.update("UPDATE automation_api_trigger_config SET enabled=false, voided=true, updated_at=NOW() WHERE id=?", id) == 0)
            throw BusinessException.notFound("接口触发配置不存在");
    }

    /** 查询全部启用且配置 Cron 的任务。 */
    public List<ApiTriggerModels.View> findEnabled() {
        return jdbcTemplate.query("""
            SELECT * FROM automation_api_trigger_config
            WHERE enabled=true AND voided=false AND cron_expression IS NOT NULL AND cron_expression<>'' ORDER BY id
            """, (rs, row) -> mapView(rs));
    }

    /** 正式执行配置并记录 PostgreSQL 执行历史。 */
    public ApiTriggerModels.ExecutionResult execute(Long id, String triggerType) {
        ApiTriggerModels.View config = get(id);
        if (config.voided() || !config.enabled()) throw new BusinessException("接口触发配置未启用");
        long startedAt = System.nanoTime();
        try {
            ApiTriggerModels.ExecutionResult result = call(config);
            saveExecution(config.id(), triggerType, "SUCCESS", result.httpStatus(), result.durationMs(), summary(result.responseBody()), null);
            return result;
        } catch (RuntimeException exception) {
            long duration = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            saveExecution(config.id(), triggerType, "FAILED", null, duration, null, summary(exception.getMessage()));
            throw exception;
        }
    }

    /** 使用未保存配置执行一次安全测试，不写 PostgreSQL 执行记录。 */
    public ApiTriggerModels.ExecutionResult test(ApiTriggerModels.Command command) {
        validate(command);
        return call(toTemporaryView(command));
    }

    /** 查询单个配置的最近执行日志。 */
    public List<ApiTriggerModels.LogView> logs(Long configId) {
        get(configId);
        return jdbcTemplate.query("""
            SELECT * FROM automation_api_trigger_log WHERE config_id=? ORDER BY triggered_at DESC LIMIT 200
            """, (rs, row) -> new ApiTriggerModels.LogView(rs.getLong("id"), rs.getLong("config_id"), rs.getString("job_id"),
            rs.getString("trigger_type"), rs.getString("status"), (Integer) rs.getObject("http_status"),
            (Long) rs.getObject("duration_ms"), rs.getString("response_summary"), rs.getString("error_message"),
            rs.getTimestamp("triggered_at").toLocalDateTime()), configId);
    }

    /** 发起认证请求和目标 HTTP 请求。 */
    private ApiTriggerModels.ExecutionResult call(ApiTriggerModels.View config) {
        JobContextHolder.checkpoint();
        URI targetUri = buildUri(urlPolicy.validate(config.url()), config.queryParams());
        RestClient client = buildClient(config.timeoutSeconds());
        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(config.httpMethod())).uri(targetUri);
        parseMap(config.headers()).forEach(spec::header);
        if (config.authEnabled()) {
            String token = fetchToken(config, client);
            spec.header(config.authTokenHeader(), config.authTokenPrefix() + token);
        }
        long startedAt = System.nanoTime();
        ResponseEntity<String> response = hasBody(config.httpMethod(), config.requestBody())
            ? spec.contentType(MediaType.parseMediaType(config.contentType())).body(config.requestBody()).retrieve().toEntity(String.class)
            : spec.retrieve().toEntity(String.class);
        JobContextHolder.checkpoint();
        return new ApiTriggerModels.ExecutionResult(response.getStatusCode().value(),
            Duration.ofNanos(System.nanoTime() - startedAt).toMillis(), response.getBody() == null ? "" : response.getBody());
    }

    /** 调用认证地址并按点路径提取 Token。 */
    private String fetchToken(ApiTriggerModels.View config, RestClient client) {
        URI authUri = urlPolicy.validate(config.authUrl());
        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(config.authMethod())).uri(authUri);
        ResponseEntity<String> response = config.authBody().isBlank() ? spec.retrieve().toEntity(String.class)
            : spec.contentType(MediaType.parseMediaType(config.authContentType())).body(config.authBody()).retrieve().toEntity(String.class);
        try {
            JsonNode node = objectMapper.readTree(response.getBody());
            for (String part : config.authTokenPath().split("\\.")) node = node.path(part);
            if (!node.isValueNode() || node.asText().isBlank()) throw new BusinessException("认证响应中未找到 Token");
            return node.asText();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("认证响应不是有效 JSON");
        }
    }

    /** 保存执行日志并更新配置最近执行摘要。 */
    private void saveExecution(Long configId, String triggerType, String status, Integer httpStatus,
                               long durationMs, String result, String error) {
        String jobId = JobContextHolder.currentJobId().orElse(null);
        jdbcTemplate.update("""
            INSERT INTO automation_api_trigger_log(config_id, job_id, trigger_type, status, http_status,
                duration_ms, response_summary, error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, configId, jobId, triggerType, status, httpStatus, durationMs, result, error);
        jdbcTemplate.update("""
            UPDATE automation_api_trigger_config SET last_trigger_at=NOW(), last_status=?, last_result=?, updated_at=NOW() WHERE id=?
            """, status, status.equals("SUCCESS") ? result : error, configId);
    }

    /** 校验方法、URL、JSON 和 Cron 表达式。 */
    private void validate(ApiTriggerModels.Command command) {
        if (command == null || text(command.name()).isBlank()) throw new BusinessException("任务名称不能为空");
        method(command.httpMethod());
        urlPolicy.validate(command.url());
        parseMap(command.headers());
        parseMap(command.queryParams());
        if (Boolean.TRUE.equals(command.authEnabled())) urlPolicy.validate(command.authUrl());
        cron(command.cronExpression());
    }

    private RestClient buildClient(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().requestFactory(factory).build();
    }

    private URI buildUri(URI base, String queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(base);
        parseMap(queryParams).forEach(builder::queryParam);
        return builder.build(true).toUri();
    }

    private Map<String, String> parseMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            JsonNode root = objectMapper.readTree(value);
            if (!root.isObject()) throw new BusinessException("请求头和查询参数必须是 JSON 对象");
            Map<String, String> result = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("JSON 配置格式错误");
        }
    }

    private ApiTriggerModels.View mapView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiTriggerModels.View(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
            rs.getString("http_method"), rs.getString("url"), cryptoService.decrypt(rs.getString("headers_encrypted")),
            rs.getString("query_params"), cryptoService.decrypt(rs.getString("request_body_encrypted")), rs.getString("content_type"),
            rs.getString("cron_expression"), rs.getInt("timeout_seconds"), rs.getBoolean("enabled"), rs.getBoolean("voided"),
            rs.getBoolean("auth_enabled"), rs.getString("auth_url"), rs.getString("auth_method"),
            cryptoService.decrypt(rs.getString("auth_body_encrypted")), rs.getString("auth_content_type"),
            rs.getString("auth_token_path"), rs.getString("auth_token_header"), rs.getString("auth_token_prefix"),
            rs.getLong("owner_user_id"), local(rs.getTimestamp("last_trigger_at")), rs.getString("last_status"),
            rs.getString("last_result"), local(rs.getTimestamp("created_at")), local(rs.getTimestamp("updated_at")));
    }

    private ApiTriggerModels.View toTemporaryView(ApiTriggerModels.Command command) {
        return new ApiTriggerModels.View(null, text(command.name()), text(command.description()), method(command.httpMethod()),
            text(command.url()), text(command.headers()), text(command.queryParams()), text(command.requestBody()),
            contentType(command.contentType()), cron(command.cronExpression()), timeout(command.timeoutSeconds()), enabled(command.enabled()),
            false, Boolean.TRUE.equals(command.authEnabled()), text(command.authUrl()), methodOrDefault(command.authMethod(), "POST"),
            text(command.authBody()), contentType(command.authContentType()), defaultText(command.authTokenPath(), "data.token"),
            defaultText(command.authTokenHeader(), "Authorization"), command.authTokenPrefix() == null ? "Bearer " : command.authTokenPrefix(),
            0L, null, null, null, null, null);
    }

    private String method(String value) {
        String normalized = defaultText(value, "GET").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(normalized)) throw new BusinessException("不支持的 HTTP 方法");
        return normalized;
    }
    private String methodOrDefault(String value, String defaultValue) { return method(defaultText(value, defaultValue)); }
    private String cron(String value) {
        if (value == null || value.isBlank()) return null;
        try { CronExpression.parse(value.trim()); return value.trim(); }
        catch (IllegalArgumentException exception) { throw new BusinessException("Cron 表达式无效"); }
    }
    private int timeout(Integer value) { return value == null ? 30 : Math.max(1, Math.min(300, value)); }
    private boolean enabled(Boolean value) { return value == null || value; }
    private String contentType(String value) { return defaultText(value, "application/json"); }
    private boolean hasBody(String method, String body) { return !Set.of("GET", "DELETE").contains(method) && body != null && !body.isBlank(); }
    private String text(String value) { return value == null ? "" : value.trim(); }
    private String defaultText(String value, String defaultValue) { return value == null || value.isBlank() ? defaultValue : value.trim(); }
    /** 截断执行结果并屏蔽常见凭证字段和 Bearer Token。 */
    private String summary(String value) {
        String text = value == null ? "" : value;
        text = text.replaceAll("(?i)(\\\"?(?:token|password|secret|authorization|cookie|api[_-]?key)\\\"?\\s*[:=]\\s*\\\")([^\\\"]*)(\\\")", "$1***$3");
        text = text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer ***");
        return text.substring(0, Math.min(resultMaxLength, text.length()));
    }
    private LocalDateTime local(java.sql.Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
}
