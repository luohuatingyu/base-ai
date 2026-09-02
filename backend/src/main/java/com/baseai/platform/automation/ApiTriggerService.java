package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.baseai.platform.security.AuthenticationType;
import com.baseai.platform.trace.TraceContextHolder;
import com.baseai.platform.trace.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ApiTriggerService {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final int MAX_REDIRECTS = 5;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCryptoService cryptoService;
    private final ApiTriggerUrlPolicy urlPolicy;
    private final int resultMaxLength;
    private final int responseMaxBytes;
    private final int requestBodyMaxBytes;
    private final int metadataMaxLength;
    private final ApiTriggerTlsTrust tlsTrust;

    public ApiTriggerService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                             ConfigCryptoService cryptoService, ApiTriggerUrlPolicy urlPolicy, PlatformProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.urlPolicy = urlPolicy;
        this.resultMaxLength = properties.getApiTrigger().getResultMaxLength();
        this.responseMaxBytes = positive(properties.getApiTrigger().getResponseMaxBytes(), 2 * 1024 * 1024);
        this.requestBodyMaxBytes = positive(properties.getApiTrigger().getRequestBodyMaxBytes(), 1024 * 1024);
        this.metadataMaxLength = positive(properties.getApiTrigger().getMetadataMaxLength(), 64 * 1024);
        this.tlsTrust = new ApiTriggerTlsTrust(properties.getApiTrigger().getCaddyCaFile());
    }

    /**
     * 按当前认证主体的资源边界查询未作废配置。
     *
     * <p>令牌管理员可管理全部配置；API Key 即使属于管理员，也始终只能访问其所有者的配置，
     * 防止一个已授权的 Key 横向调用其他自动化任务。</p>
     */
    public List<ApiTriggerModels.View> listForCurrentUser(String keyword, Boolean enabled) {
        AuthUser user = AuthContext.require();
        return list(keyword, enabled, canManageAll(user) ? null : user.id());
    }

    /** 同包调度与维护逻辑查询全部未作废配置，不可由 HTTP 入口直接调用。 */
    List<ApiTriggerModels.View> list(String keyword, Boolean enabled) {
        return list(keyword, enabled, null);
    }

    /** 复用查询条件并可选附加所有者过滤，避免两条查询路径产生行为漂移。 */
    private List<ApiTriggerModels.View> list(String keyword, Boolean enabled, Long ownerUserId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM automation_api_trigger_config WHERE voided=false");
        List<Object> args = new ArrayList<>();
        if (ownerUserId != null) {
            sql.append(" AND owner_user_id=?");
            args.add(ownerUserId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(name) LIKE LOWER(?) OR LOWER(description) LIKE LOWER(?))");
            args.add("%" + keyword.trim() + "%"); args.add("%" + keyword.trim() + "%");
        }
        if (enabled != null) { sql.append(" AND enabled=?"); args.add(enabled); }
        sql.append(" ORDER BY id DESC");
        return jdbcTemplate.query(sql.toString(), (rs, row) -> mapView(rs), args.toArray());
    }

    /** 创建当前登录用户拥有的接口触发配置。 */
    public ApiTriggerModels.View createForCurrentUser(ApiTriggerModels.Command command) {
        return create(command, AuthContext.require().id());
    }

    /** 更新当前主体有权访问的接口触发配置。 */
    public ApiTriggerModels.View updateForCurrentUser(Long id, ApiTriggerModels.Command command) {
        requireAccessible(id);
        return update(id, command);
    }

    /** 查询当前主体有权访问的接口触发配置。 */
    public ApiTriggerModels.View getForCurrentUser(Long id) {
        return requireAccessible(id);
    }

    /** 停用当前主体有权访问的接口触发配置。 */
    public void disableForCurrentUser(Long id) {
        requireAccessible(id);
        disable(id);
    }

    /** 作废当前主体有权访问的接口触发配置。 */
    public void voidForCurrentUser(Long id) {
        requireAccessible(id);
        voidConfig(id);
    }

    /** 执行当前主体有权访问的接口触发配置。 */
    public ApiTriggerModels.ExecutionResult executeForCurrentUser(Long id, String triggerType) {
        requireAccessible(id);
        return execute(id, triggerType);
    }

    /** 查询当前主体有权访问的接口触发配置日志。 */
    public List<ApiTriggerModels.LogView> logsForCurrentUser(Long configId, String traceId) {
        requireAccessible(configId);
        return logs(configId, traceId);
    }

    /**
     * 创建接口触发配置并加密敏感字段。
     *
     * <p>仅供同包的调度和迁移内部逻辑使用，HTTP 入口必须调用
     * {@link #createForCurrentUser(ApiTriggerModels.Command)}。</p>
     */
    ApiTriggerModels.View create(ApiTriggerModels.Command command, Long ownerUserId) {
        validate(command);
        Long id = insertAndReturnId("""
            INSERT INTO automation_api_trigger_config(name, description, http_method, url, headers_encrypted,
                query_params, request_body_encrypted, content_type, cron_expression, timeout_seconds, enabled,
                auth_enabled, auth_url, auth_method, auth_body_encrypted, auth_content_type, auth_token_path,
                auth_token_header, auth_token_prefix, owner_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, text(command.name()), text(command.description()), method(command.httpMethod()), text(command.url()),
            cryptoService.encrypt(text(command.headers())), text(command.queryParams()), cryptoService.encrypt(text(command.requestBody())),
            contentType(command.contentType()), cron(command.cronExpression()), timeout(command.timeoutSeconds()), enabled(command.enabled()),
            Boolean.TRUE.equals(command.authEnabled()), text(command.authUrl()), methodOrDefault(command.authMethod(), "POST"),
            cryptoService.encrypt(text(command.authBody())), contentType(command.authContentType()), defaultText(command.authTokenPath(), "data.token"),
            defaultText(command.authTokenHeader(), "Authorization"), command.authTokenPrefix() == null ? "Bearer " : command.authTokenPrefix(), ownerUserId);
        return get(id);
    }

    /** 同包系统调用的更新实现；HTTP 入口必须先完成资源所有权校验。 */
    ApiTriggerModels.View update(Long id, ApiTriggerModels.Command command) {
        get(id);
        validate(command);
        jdbcTemplate.update("""
            UPDATE automation_api_trigger_config SET name=?, description=?, http_method=?, url=?, headers_encrypted=?,
                query_params=?, request_body_encrypted=?, content_type=?, cron_expression=?, timeout_seconds=?, enabled=?,
                auth_enabled=?, auth_url=?, auth_method=?, auth_body_encrypted=?, auth_content_type=?, auth_token_path=?,
                auth_token_header=?, auth_token_prefix=?, updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND voided=false
            """, text(command.name()), text(command.description()), method(command.httpMethod()), text(command.url()),
            cryptoService.encrypt(text(command.headers())), text(command.queryParams()), cryptoService.encrypt(text(command.requestBody())),
            contentType(command.contentType()), cron(command.cronExpression()), timeout(command.timeoutSeconds()), enabled(command.enabled()),
            Boolean.TRUE.equals(command.authEnabled()), text(command.authUrl()), methodOrDefault(command.authMethod(), "POST"),
            cryptoService.encrypt(text(command.authBody())), contentType(command.authContentType()), defaultText(command.authTokenPath(), "data.token"),
            defaultText(command.authTokenHeader(), "Authorization"), command.authTokenPrefix() == null ? "Bearer " : command.authTokenPrefix(), id);
        return get(id);
    }

    /** 同包系统调用的读取实现，不包含调用者资源校验。 */
    ApiTriggerModels.View get(Long id) {
        List<ApiTriggerModels.View> rows = jdbcTemplate.query("SELECT * FROM automation_api_trigger_config WHERE id=?",
            (rs, row) -> mapView(rs), id);
        if (rows.isEmpty()) throw BusinessException.notFound("apiTrigger.notFound");
        return rows.get(0);
    }

    /** 同包系统调用的停用实现，调用前必须完成资源所有权校验。 */
    void disable(Long id) {
        if (jdbcTemplate.update("UPDATE automation_api_trigger_config SET enabled=false, updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND voided=false", id) == 0)
            throw BusinessException.notFound("apiTrigger.notFound");
    }

    /** 同包系统调用的作废实现，调用前必须完成资源所有权校验。 */
    void voidConfig(Long id) {
        if (jdbcTemplate.update("UPDATE automation_api_trigger_config SET enabled=false, voided=true, updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id) == 0)
            throw BusinessException.notFound("apiTrigger.notFound");
    }

    /** 查询全部启用且配置 Cron 的任务，仅供调度器初始化使用。 */
    List<ApiTriggerModels.View> findEnabled() {
        return jdbcTemplate.query("""
            SELECT * FROM automation_api_trigger_config
            WHERE enabled=true AND voided=false AND cron_expression IS NOT NULL AND cron_expression<>'' ORDER BY id
            """, (rs, row) -> mapView(rs));
    }

    /** 同包系统调用的执行实现，调用前必须完成资源所有权校验。 */
    ApiTriggerModels.ExecutionResult execute(Long id, String triggerType) {
        ApiTriggerModels.View config = get(id);
        if (config.voided() || !config.enabled()) throw new BusinessException("apiTrigger.disabled");
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

    /** 使用未保存配置执行一次安全测试，不写 MySQL 执行记录。 */
    public ApiTriggerModels.ExecutionResult test(ApiTriggerModels.Command command) {
        validate(command);
        return call(toTemporaryView(command));
    }

    /** 同包系统调用的日志查询实现，调用前必须完成资源所有权校验。 */
    List<ApiTriggerModels.LogView> logs(Long configId, String traceId) {
        get(configId);
        StringBuilder sql = new StringBuilder("SELECT * FROM automation_api_trigger_log WHERE config_id=?");
        List<Object> args = new ArrayList<>();
        args.add(configId);
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id=?");
            args.add(traceId.trim());
        }
        sql.append(" ORDER BY triggered_at DESC LIMIT 200");
        return jdbcTemplate.query(sql.toString(), (rs, row) -> new ApiTriggerModels.LogView(
            rs.getLong("id"), rs.getLong("config_id"), rs.getString("trace_id"),
            rs.getString("trigger_type"), rs.getString("status"), (Integer) rs.getObject("http_status"),
            (Long) rs.getObject("duration_ms"), rs.getString("response_summary"), rs.getString("error_message"),
            rs.getTimestamp("triggered_at").toLocalDateTime()), args.toArray());
    }

    /** 校验当前认证主体是否可访问指定配置，并以未找到响应隐藏其他所有者的资源。 */
    private ApiTriggerModels.View requireAccessible(Long id) {
        ApiTriggerModels.View config = get(id);
        AuthUser user = AuthContext.require();
        if (!canManageAll(user) && !Objects.equals(config.ownerUserId(), user.id())) {
            throw BusinessException.notFound("apiTrigger.notFound");
        }
        return config;
    }

    /** 仅会话令牌中的管理员角色可跨所有者管理；API Key 永远不可取得该豁免。 */
    private boolean canManageAll(AuthUser user) {
        return user.authenticationType() != AuthenticationType.API_KEY && user.roles().contains("ADMIN");
    }

    /** 发起认证请求和目标 HTTP 请求。 */
    private ApiTriggerModels.ExecutionResult call(ApiTriggerModels.View config) {
        TraceContextHolder.checkpoint();
        URI targetUri = buildUri(urlPolicy.validate(config.url()), config.queryParams());
        try (OutboundClient outbound = buildClient(config.timeoutSeconds())) {
            RestClient client = outbound.client();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            parseMap(config.headers()).forEach((name, value) -> headers.put(name, new ArrayList<>(List.of(value))));
            if (config.authEnabled()) {
                String token = fetchToken(config, client);
                headers.computeIfAbsent(config.authTokenHeader(), ignored -> new ArrayList<>())
                    .add(config.authTokenPrefix() + token);
            }
            long startedAt = System.nanoTime();
            OutboundRequest request = new OutboundRequest(config.httpMethod(), targetUri, headers,
                hasBody(config.httpMethod(), config.requestBody()) ? config.requestBody() : null, config.contentType());
            LimitedResponse response = exchange(client, request);
            TraceContextHolder.checkpoint();
            return new ApiTriggerModels.ExecutionResult(response.status(),
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                decodeResponseBody(response.body(), response.contentType()));
        }
    }

    /** 调用认证地址并按点路径提取 Token。 */
    private String fetchToken(ApiTriggerModels.View config, RestClient client) {
        URI authUri = urlPolicy.validate(config.authUrl());
        OutboundRequest request = new OutboundRequest(config.authMethod(), authUri, Map.of(),
            config.authBody().isBlank() ? null : config.authBody(), config.authContentType());
        LimitedResponse response = exchange(client, request);
        try {
            JsonNode node = objectMapper.readTree(decodeResponseBody(response.body(), response.contentType()));
            for (String part : config.authTokenPath().split("\\.")) node = node.path(part);
            if (!node.isValueNode() || node.asText().isBlank()) throw new BusinessException("apiTrigger.authTokenMissing");
            return node.asText();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("apiTrigger.authResponseInvalidJson");
        }
    }

    /** 按上游声明字符集解码响应正文，未声明时按 JSON 常用的 UTF-8 处理。 */
    private String decodeResponseBody(byte[] body, MediaType contentType) {
        if (body == null || body.length == 0) return "";
        Charset charset = contentType == null ? null : contentType.getCharset();
        return new String(body, charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /** 保存执行日志并更新配置最近执行摘要。 */
    private void saveExecution(Long configId, String triggerType, String status, Integer httpStatus,
                               long durationMs, String result, String error) {
        String traceId = TraceContextHolder.currentTraceId().orElse(null);
        jdbcTemplate.update("""
            INSERT INTO automation_api_trigger_log(config_id, trace_id, trigger_type, status, http_status,
                duration_ms, response_summary, error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, configId, traceId, triggerType, status, httpStatus, durationMs, result, error);
        jdbcTemplate.update("""
            UPDATE automation_api_trigger_config SET last_trigger_at=CURRENT_TIMESTAMP(6), last_status=?, last_result=?,
                updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
            """, status, status.equals("SUCCESS") ? result : error, configId);
    }

    /** 在 MySQL 上执行插入并回填自增主键，替代 PostgreSQL 的 RETURNING 语法。 */
    private Long insertAndReturnId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            // 显式声明只回读主键列，避免驱动把带默认值的列一并作为生成键返回
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            new ArgumentPreparedStatementSetter(args).setValues(statement);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new BusinessException("apiTrigger.createFailed");
        return key.longValue();
    }

    /** 校验方法、URL、JSON 和 Cron 表达式。 */
    private void validate(ApiTriggerModels.Command command) {
        if (command == null || text(command.name()).isBlank()) throw new BusinessException("apiTrigger.nameRequired");
        validateLength(command.url(), 2048, "apiTrigger.urlTooLong");
        validateLength(command.authUrl(), 2048, "apiTrigger.urlTooLong");
        validateLength(command.headers(), metadataMaxLength, "apiTrigger.metadataTooLarge");
        validateLength(command.queryParams(), metadataMaxLength, "apiTrigger.metadataTooLarge");
        validateLength(command.requestBody(), requestBodyMaxBytes, "apiTrigger.requestBodyTooLarge");
        validateLength(command.authBody(), requestBodyMaxBytes, "apiTrigger.requestBodyTooLarge");
        method(command.httpMethod());
        urlPolicy.validate(command.url());
        parseMap(command.headers());
        parseMap(command.queryParams());
        if (Boolean.TRUE.equals(command.authEnabled())) urlPolicy.validate(command.authUrl());
        cron(command.cronExpression());
    }

    /**
     * 创建把安全 DNS 解析结果直接交给连接层的短生命周期 HTTP 客户端。
     *
     * <p>不使用 JDK 默认 DNS 连接器，确保通过 URL 策略审核的地址就是实际 Socket 连接使用的地址。</p>
     */
    private OutboundClient buildClient(int timeoutSeconds) {
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try {
                    return urlPolicy.resolveVerifiedHost(host);
                } catch (BusinessException exception) {
                    UnknownHostException rejected = new UnknownHostException("outbound host rejected by policy");
                    rejected.initCause(exception);
                    throw rejected;
                }
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                resolve(host);
                return host;
            }
        };
        PoolingHttpClientConnectionManagerBuilder manager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver).setMaxConnTotal(1).setMaxConnPerRoute(1);
        SSLSocketFactory socketFactory = tlsTrust.socketFactory();
        if (socketFactory != null) {
            manager.setSSLSocketFactory(new SSLConnectionSocketFactory(socketFactory, new DefaultHostnameVerifier()));
        }
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(timeoutSeconds))
            .setConnectTimeout(Timeout.ofSeconds(timeoutSeconds))
            .setResponseTimeout(Timeout.ofSeconds(timeoutSeconds))
            .setRedirectsEnabled(false)
            .build();
        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(manager.build())
            .setDefaultRequestConfig(requestConfig)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        RestClient client = RestClient.builder().requestFactory(factory)
            .requestInterceptor((request, body, execution) -> {
                // 每个重定向跳转仍先做 URL 语义校验；实际连接由上方 DnsResolver 使用已验证地址。
                urlPolicy.validate(request.getURI().toString());
                return execution.execute(request, body);
            }).build();
        TraceContextHolder.current().map(TraceContext::runtime).ifPresent(runtime -> runtime.registerCloseable(httpClient));
        return new OutboundClient(client, httpClient);
    }

    /** 手动跟随受控重定向，并在每一跳重新执行 URL 安全策略。 */
    private LimitedResponse exchange(RestClient client, OutboundRequest initial) {
        OutboundRequest current = initial;
        Set<String> visited = new LinkedHashSet<>();
        visited.add(current.uri().normalize().toString());
        for (int redirects = 0; ; redirects++) {
            RemoteResponse response = execute(client, current);
            if (response.status() < 300 || response.status() >= 400) {
                if (response.status() >= 400) {
                    throw new BusinessException(502, "apiTrigger.remoteError", response.status());
                }
                return new LimitedResponse(response.status(), response.body(), response.contentType());
            }
            if (redirects >= MAX_REDIRECTS) {
                throw new BusinessException(502, "apiTrigger.redirectLimitExceeded");
            }
            current = redirectedRequest(current, response.status(), response.location());
            if (!visited.add(current.uri().normalize().toString())) {
                throw new BusinessException(502, "apiTrigger.redirectLimitExceeded");
            }
        }
    }

    /** 执行单次请求，保留重定向地址但不让 JDK 自动访问下一跳。 */
    private RemoteResponse execute(RestClient client, OutboundRequest request) {
        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(request.method())).uri(request.uri());
        request.headers().forEach((name, values) -> values.forEach(value -> spec.header(name, value)));
        RestClient.RequestHeadersSpec<?> prepared = request.body() == null ? spec
            : spec.contentType(MediaType.parseMediaType(request.contentType())).body(request.body());
        return prepared.exchange((clientRequest, clientResponse) -> {
            int status = clientResponse.getStatusCode().value();
            if (clientResponse.getStatusCode().is3xxRedirection()) {
                return new RemoteResponse(status, new byte[0], clientResponse.getHeaders().getContentType(),
                    clientResponse.getHeaders().getFirst("Location"));
            }
            return new RemoteResponse(status, readLimitedBody(clientResponse.getBody()),
                clientResponse.getHeaders().getContentType(), null);
        });
    }

    /** 校验下一跳 Host、协议、状态码与方法语义，并保留 307/308 的请求内容。 */
    private OutboundRequest redirectedRequest(OutboundRequest current, int status, String location) {
        if (!REDIRECT_STATUSES.contains(status) || location == null || location.isBlank()) {
            throw new BusinessException(502, "apiTrigger.redirectLocationInvalid");
        }
        URI redirectUri;
        try {
            redirectUri = current.uri().resolve(URI.create(location.trim()));
        } catch (Exception exception) {
            throw new BusinessException(502, "apiTrigger.redirectLocationInvalid");
        }
        URI validated = urlPolicy.validate(redirectUri.toString());
        if (!current.uri().getHost().equalsIgnoreCase(validated.getHost())) {
            throw new BusinessException(502, "apiTrigger.redirectHostForbidden");
        }
        if ("https".equalsIgnoreCase(current.uri().getScheme())
            && "http".equalsIgnoreCase(validated.getScheme())) {
            throw new BusinessException(502, "apiTrigger.redirectDowngradeForbidden");
        }
        if (!"GET".equals(current.method()) && status != 307 && status != 308) {
            throw new BusinessException(502, "apiTrigger.redirectMethodForbidden");
        }
        return new OutboundRequest(current.method(), validated, current.headers(), current.body(), current.contentType());
    }

    /** 最多读取配置上限再多一个字节，以识别未声明长度的超大响应。 */
    private byte[] readLimitedBody(InputStream input) throws IOException {
        byte[] body = input.readNBytes(responseMaxBytes + 1);
        if (body.length > responseMaxBytes) throw new BusinessException(502, "apiTrigger.responseTooLarge");
        return body;
    }

    /** 按 UTF-8 字节数限制可持久化或发送的外部配置内容。 */
    private void validateLength(String value, int maximum, String messageKey) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new BusinessException(messageKey, maximum);
        }
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
            if (!root.isObject()) throw new BusinessException("apiTrigger.mapMustBeObject");
            Map<String, String> result = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("apiTrigger.jsonConfigurationInvalid");
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
        if (!METHODS.contains(normalized)) throw new BusinessException("apiTrigger.httpMethodUnsupported");
        return normalized;
    }
    private String methodOrDefault(String value, String defaultValue) { return method(defaultText(value, defaultValue)); }
    private String cron(String value) {
        if (value == null || value.isBlank()) return null;
        try { CronExpression.parse(value.trim()); return value.trim(); }
        catch (IllegalArgumentException exception) { throw new BusinessException("apiTrigger.cronInvalid"); }
    }
    private int timeout(Integer value) { return value == null ? 30 : Math.max(1, Math.min(300, value)); }
    private int positive(int value, int fallback) { return Math.min(100 * 1024 * 1024, value > 0 ? value : fallback); }
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

    private record OutboundRequest(String method, URI uri, Map<String, List<String>> headers,
                                   String body, String contentType) {}

    private record RemoteResponse(int status, byte[] body, MediaType contentType, String location) {}

    private record LimitedResponse(int status, byte[] body, MediaType contentType) {}

    /** 每次触发执行后关闭客户端和 Socket，避免短任务积累连接资源。 */
    private record OutboundClient(RestClient client, CloseableHttpClient httpClient) implements AutoCloseable {
        @Override
        public void close() {
            try {
                httpClient.close();
            } catch (IOException ignored) {
                // 客户端已完成或因任务取消关闭时，无需覆盖原始业务结果。
            }
        }
    }
}
