package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接口触发器 MySQL 持久化测试。
 *
 * <p>在 MySQL 兼容模式的 H2 上执行真实的 MySQL 迁移脚本，覆盖自增主键回填、BIT(1) 布尔映射、
 * DATETIME(6) 精度和全部查询语句，验证从 PostgreSQL 迁移到 MySQL 后行为不变。</p>
 */
class ApiTriggerServicePersistenceTest {
    private static final String MIGRATION = "db/migration/mysql/V1__create_platform_schema.sql";
    private static final String SECTION_MARKER = "-- ==================== API 触发自动化 ====================";
    private static final long OWNER = 7L;

    private final List<HttpServer> servers = new ArrayList<>();
    private JdbcTemplate jdbcTemplate;
    private ApiTriggerUrlPolicy urlPolicy;
    private ConfigCryptoService cryptoService;
    private ApiTriggerService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:api-trigger-" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbcTemplate = new JdbcTemplate(dataSource);
        applyMysqlMigration();

        PlatformProperties properties = new PlatformProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        cryptoService = new ConfigCryptoService(properties);
        urlPolicy = mock(ApiTriggerUrlPolicy.class);
        when(urlPolicy.validate(anyString())).thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        service = new ApiTriggerService(jdbcTemplate, new ObjectMapper(), cryptoService, urlPolicy, properties);
    }

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
        jdbcTemplate.execute("DROP ALL OBJECTS");
    }

    /** 创建配置应回填 MySQL 自增主键并完整持久化全部字段。 */
    @Test
    void createReturnsGeneratedIdAndPersistsAllFields() {
        ApiTriggerModels.View created = service.create(command("Daily Sync", "0 0 * * * *", true), OWNER);

        assertNotNull(created.id());
        assertTrue(created.id() > 0);
        ApiTriggerModels.View loaded = service.get(created.id());
        assertEquals("Daily Sync", loaded.name());
        assertEquals("GET", loaded.httpMethod());
        assertEquals("0 0 * * * *", loaded.cronExpression());
        assertEquals(OWNER, loaded.ownerUserId());
        assertEquals("{\"X-Token\":\"secret\"}", loaded.headers());
        assertNotNull(loaded.createdAt());
        assertNotNull(loaded.updatedAt());
    }

    /** 连续创建应返回严格递增且互不相同的主键。 */
    @Test
    void createReturnsDistinctIncreasingIds() {
        Long first = service.create(command("First", null, true), OWNER).id();
        Long second = service.create(command("Second", null, true), OWNER).id();

        assertTrue(second > first);
    }

    /** 加密字段应经 MEDIUMTEXT 列往返后解密还原，且密文不落明文。 */
    @Test
    void encryptedColumnsRoundTripThroughMediumText() {
        String body = "{\"password\":\"明文口令\"}";
        ApiTriggerModels.View created = service.create(new ApiTriggerModels.Command("Secret", "d", "POST",
            "https://example.com/api", "{\"Authorization\":\"Bearer abc\"}", "{}", body, "application/json",
            null, 30, true, false, "", "POST", "", "application/json", "data.token", "Authorization", "Bearer "), OWNER);

        String stored = jdbcTemplate.queryForObject(
            "SELECT request_body_encrypted FROM automation_api_trigger_config WHERE id=?", String.class, created.id());
        assertFalse(stored.contains("明文口令"));
        assertEquals(body, service.get(created.id()).requestBody());
    }

    /** 更新应改写字段并推进 updated_at。 */
    @Test
    void updateModifiesFieldsAndAdvancesUpdatedAt() throws Exception {
        ApiTriggerModels.View created = service.create(command("Before", null, true), OWNER);
        Thread.sleep(5);

        ApiTriggerModels.View updated = service.update(created.id(),
            new ApiTriggerModels.Command("After", "changed", "POST", "https://example.com/v2", "{}", "{}", "{\"a\":1}",
                "application/json", "0 5 * * * *", 60, false, false, "", "POST", "", "application/json",
                "data.token", "Authorization", "Bearer "));

        assertEquals("After", updated.name());
        assertEquals("POST", updated.httpMethod());
        assertEquals(60, updated.timeoutSeconds());
        assertFalse(updated.enabled());
        assertTrue(updated.updatedAt().isAfter(created.updatedAt()));
    }

    /** BIT(1) 列应与 Java 布尔值双向一致，作废配置从列表隐藏但仍可直接读取。 */
    @Test
    void booleanColumnsRoundTripAndVoidedConfigurationIsHidden() {
        ApiTriggerModels.View created = service.create(command("Voidable", null, true), OWNER);
        assertTrue(created.enabled());
        assertFalse(created.voided());

        service.voidConfig(created.id());

        ApiTriggerModels.View loaded = service.get(created.id());
        assertTrue(loaded.voided());
        assertFalse(loaded.enabled());
        assertTrue(service.list(null, null).isEmpty());
    }

    /** 停用应仅关闭启用标记，作废标记保持不变。 */
    @Test
    void disableOnlyClearsEnabledFlag() {
        ApiTriggerModels.View created = service.create(command("Disabled", null, true), OWNER);

        service.disable(created.id());

        ApiTriggerModels.View loaded = service.get(created.id());
        assertFalse(loaded.enabled());
        assertFalse(loaded.voided());
        assertEquals(1, service.list(null, null).size());
    }

    /** 关键字查询应对名称和描述大小写不敏感，并支持中文。 */
    @ParameterizedTest
    @CsvSource({"daily,1", "DAILY,1", "Sync,1", "同步,1", "absent,0"})
    void listMatchesKeywordCaseInsensitively(String keyword, int expected) {
        service.create(new ApiTriggerModels.Command("Daily Sync", "每日同步任务", "GET", "https://example.com/api",
            "{}", "{}", "", "application/json", null, 30, true, false, "", "POST", "", "application/json",
            "data.token", "Authorization", "Bearer "), OWNER);

        assertEquals(expected, service.list(keyword, null).size());
    }

    /** 启用状态过滤应基于 BIT(1) 列精确匹配。 */
    @Test
    void listFiltersByEnabledFlag() {
        service.create(command("Enabled", null, true), OWNER);
        service.create(command("Paused", null, false), OWNER);

        assertEquals(1, service.list(null, true).size());
        assertEquals(1, service.list(null, false).size());
        assertEquals(2, service.list(null, null).size());
    }

    /** 调度扫描应只返回启用、未作废且配置了 Cron 的任务。 */
    @Test
    void findEnabledReturnsOnlyScheduledConfigurations() {
        ApiTriggerModels.View scheduled = service.create(command("Scheduled", "0 0 * * * *", true), OWNER);
        service.create(command("NoCron", null, true), OWNER);
        service.create(command("BlankCron", "", true), OWNER);
        service.create(command("Paused", "0 0 * * * *", false), OWNER);
        ApiTriggerModels.View voided = service.create(command("Voided", "0 0 * * * *", true), OWNER);
        service.voidConfig(voided.id());

        List<ApiTriggerModels.View> enabled = service.findEnabled();

        assertEquals(1, enabled.size());
        assertEquals(scheduled.id(), enabled.get(0).id());
    }

    /** 执行成功应写入日志并回填最近执行状态。 */
    @Test
    void executeWritesSuccessLogAndBackfillsLastStatus() throws Exception {
        HttpServer server = server(200, "{\"data\":\"ok\"}");
        ApiTriggerModels.View created = service.create(commandForUrl("Remote", url(server)), OWNER);

        ApiTriggerModels.ExecutionResult result = service.execute(created.id(), "MANUAL");

        assertEquals(200, result.httpStatus());
        List<ApiTriggerModels.LogView> logs = service.logs(created.id(), null);
        assertEquals(1, logs.size());
        assertEquals("SUCCESS", logs.get(0).status());
        assertEquals("MANUAL", logs.get(0).triggerType());
        assertEquals(200, logs.get(0).httpStatus());
        assertEquals("{\"data\":\"ok\"}", logs.get(0).responseSummary());
        assertNull(logs.get(0).errorMessage());
        assertNull(logs.get(0).traceId());

        ApiTriggerModels.View reloaded = service.get(created.id());
        assertEquals("SUCCESS", reloaded.lastStatus());
        assertNotNull(reloaded.lastTriggerAt());
    }

    /** 执行失败应写入失败日志且不写入 HTTP 状态。 */
    @Test
    void executeWritesFailureLogWhenRemoteReturnsError() throws Exception {
        HttpServer server = server(500, "boom");
        ApiTriggerModels.View created = service.create(commandForUrl("Broken", url(server)), OWNER);

        assertThrows(BusinessException.class, () -> service.execute(created.id(), "SCHEDULED"));

        List<ApiTriggerModels.LogView> logs = service.logs(created.id(), null);
        assertEquals(1, logs.size());
        assertEquals("FAILED", logs.get(0).status());
        assertNull(logs.get(0).httpStatus());
        assertNull(logs.get(0).responseSummary());
        assertNotNull(logs.get(0).errorMessage());
        assertEquals("FAILED", service.get(created.id()).lastStatus());
    }

    /** 执行摘要应按配置上限截断后再写入日志列。 */
    @Test
    void executionSummaryIsTruncatedToConfiguredLimit() throws Exception {
        PlatformProperties limited = new PlatformProperties();
        limited.setConfigEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        limited.getApiTrigger().setResultMaxLength(8);
        ApiTriggerService truncating = new ApiTriggerService(jdbcTemplate, new ObjectMapper(), cryptoService,
            urlPolicy, limited);
        HttpServer server = server(200, "0123456789abcdef");
        ApiTriggerModels.View created = truncating.create(commandForUrl("Long", url(server)), OWNER);

        truncating.execute(created.id(), "MANUAL");

        assertEquals("01234567", truncating.logs(created.id(), null).get(0).responseSummary());
    }

    /** 同一毫秒内写入的日志应凭 DATETIME(6) 精度保持稳定倒序。 */
    @Test
    void logsKeepStableDescendingOrderWithinSameMillisecond() {
        ApiTriggerModels.View created = service.create(command("Ordered", null, true), OWNER);
        LocalDateTime base = LocalDateTime.of(2026, 8, 24, 10, 0, 0, 500_000_000);
        insertLog(created.id(), "trace-a", base.plusNanos(1_000));
        insertLog(created.id(), "trace-b", base.plusNanos(2_000));
        insertLog(created.id(), "trace-c", base.plusNanos(3_000));

        List<ApiTriggerModels.LogView> logs = service.logs(created.id(), null);

        assertEquals(List.of("trace-c", "trace-b", "trace-a"), logs.stream().map(ApiTriggerModels.LogView::traceId).toList());
    }

    /** 日志查询应限定在指定配置内，不泄漏其他配置的执行记录。 */
    @Test
    void logsDoNotLeakAcrossConfigurations() {
        ApiTriggerModels.View mine = service.create(command("Mine", null, true), OWNER);
        ApiTriggerModels.View other = service.create(command("Other", null, true), 99L);
        insertLog(mine.id(), "trace-mine", LocalDateTime.now());
        insertLog(other.id(), "trace-other", LocalDateTime.now());

        List<ApiTriggerModels.LogView> logs = service.logs(mine.id(), null);

        assertEquals(1, logs.size());
        assertEquals("trace-mine", logs.get(0).traceId());
        assertEquals(mine.id(), logs.get(0).configId());
    }

    /** Trace ID 过滤应精确匹配，不允许模糊命中其他任务的记录。 */
    @Test
    void logsFilterByExactTraceId() {
        ApiTriggerModels.View created = service.create(command("Traced", null, true), OWNER);
        insertLog(created.id(), "trace-1", LocalDateTime.now());
        insertLog(created.id(), "trace-12", LocalDateTime.now());

        assertEquals(1, service.logs(created.id(), " trace-1 ").size());
        assertEquals("trace-1", service.logs(created.id(), "trace-1").get(0).traceId());
        assertTrue(service.logs(created.id(), "trace-9").isEmpty());
    }

    /** 对不存在的配置执行读写操作应统一返回未找到。 */
    @ParameterizedTest
    @ValueSource(strings = {"get", "disable", "void", "logs", "update", "execute"})
    void missingConfigurationIsRejected(String operation) {
        BusinessException exception = assertThrows(BusinessException.class, () -> invoke(operation, 4321L));

        assertEquals("apiTrigger.notFound", exception.getMessageKey());
        assertEquals(404, exception.getStatus());
    }

    /** 已停用的配置不允许执行。 */
    @Test
    void disabledConfigurationCannotBeExecuted() {
        ApiTriggerModels.View created = service.create(command("Paused", null, false), OWNER);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.execute(created.id(), "MANUAL"));

        assertEquals("apiTrigger.disabled", exception.getMessageKey());
    }

    /** 空请求头、空正文等边界输入应可写入非空 MEDIUMTEXT 列。 */
    @Test
    void blankOptionalFieldsArePersistedAsEmptyText() {
        ApiTriggerModels.View created = service.create(new ApiTriggerModels.Command("Blank", null, "GET",
            "https://example.com/api", null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null), OWNER);

        ApiTriggerModels.View loaded = service.get(created.id());
        assertEquals("", loaded.description());
        assertEquals("", loaded.headers());
        assertEquals("", loaded.requestBody());
        assertEquals("application/json", loaded.contentType());
        assertEquals("Bearer ", loaded.authTokenPrefix());
        assertNull(loaded.cronExpression());
    }

    /**
     * 执行基线迁移中的接口触发段落。
     *
     * <p>基线脚本包含全部平台表，此处只截取接口触发自动化章节，避免在 H2 上创建无关表；
     * H2 不支持 MySQL 的位串字面量，仅替换默认值写法。</p>
     */
    private void applyMysqlMigration() {
        String script;
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取 MySQL 迁移脚本", exception);
        }
        int start = script.indexOf(SECTION_MARKER);
        if (start < 0) throw new IllegalStateException("迁移脚本缺少接口触发章节");
        int end = script.indexOf("-- ====================", start + SECTION_MARKER.length());
        String section = end < 0 ? script.substring(start) : script.substring(start, end);
        section = section.replace("b'1'", "TRUE").replace("b'0'", "FALSE");
        for (String statement : section.split(";")) {
            if (!statement.isBlank()) jdbcTemplate.execute(statement);
        }
    }

    /** 直接写入日志行，用于构造精确时间戳和跨配置隔离场景。 */
    private void insertLog(Long configId, String traceId, LocalDateTime triggeredAt) {
        jdbcTemplate.update("""
            INSERT INTO automation_api_trigger_log(config_id, trace_id, trigger_type, status, http_status,
                duration_ms, response_summary, error_message, triggered_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, configId, traceId, "MANUAL", "SUCCESS", 200, 12L, "ok", null, Timestamp.valueOf(triggeredAt));
    }

    private void invoke(String operation, Long id) {
        switch (operation) {
            case "get" -> service.get(id);
            case "disable" -> service.disable(id);
            case "void" -> service.voidConfig(id);
            case "logs" -> service.logs(id, null);
            case "update" -> service.update(id, command("Missing", null, true));
            default -> service.execute(id, "MANUAL");
        }
    }

    private ApiTriggerModels.Command command(String name, String cron, boolean enabled) {
        return new ApiTriggerModels.Command(name, "描述", "GET", "https://example.com/api",
            "{\"X-Token\":\"secret\"}", "{}", "", "application/json", cron, 30, enabled, false, "", "POST", "",
            "application/json", "data.token", "Authorization", "Bearer ");
    }

    private ApiTriggerModels.Command commandForUrl(String name, String url) {
        return new ApiTriggerModels.Command(name, "描述", "GET", url, "{}", "{}", "", "application/json",
            null, 5, true, false, "", "POST", "", "application/json", "data.token", "Authorization", "Bearer ");
    }

    private HttpServer server(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/target", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return server;
    }

    private String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/target";
    }
}
