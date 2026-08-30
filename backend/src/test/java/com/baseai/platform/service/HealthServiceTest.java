package com.baseai.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthServiceTest {
    private JdbcTemplate mysql;
    private JdbcTemplate postgresql;
    private StringRedisTemplate redis;
    private RestClient worker;
    @SuppressWarnings("rawtypes")
    private RestClient.RequestHeadersUriSpec request;
    private RestClient.ResponseSpec response;
    private HealthService service;

    /** 初始化四项就绪依赖的成功响应。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mysql = mock(JdbcTemplate.class);
        postgresql = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        worker = mock(RestClient.class);
        request = mock(RestClient.RequestHeadersUriSpec.class);
        response = mock(RestClient.ResponseSpec.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(mysql.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(postgresql.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        when(worker.get()).thenReturn(request);
        when(request.uri("/health")).thenReturn(request);
        when(request.retrieve()).thenReturn(response);
        when(response.body(Map.class)).thenReturn(Map.of("status", "UP"));
        service = new HealthService(mysql, postgresql, redis, worker);
    }

    /** 四项依赖均正常时服务就绪。 */
    @Test
    void reportsReadyWhenAllDependenciesRespond() {
        assertTrue(service.isReady());
    }

    /** 未启用 PostgreSQL 时不得影响平台就绪状态。 */
    @Test
    void reportsReadyWithoutPostgresql() {
        service = new HealthService(mysql, null, redis, worker);

        assertTrue(service.isReady());
    }

    /** 显式启用 PostgreSQL 后，其故障必须使平台不就绪。 */
    @Test
    void reportsUnavailableWhenEnabledPostgresqlFails() {
        when(postgresql.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("down"));

        assertFalse(service.isReady());
    }

    /** 任一数据库失败时不得继续报告就绪。 */
    @Test
    void reportsUnavailableWhenDatabaseFails() {
        when(mysql.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("down"));

        assertFalse(service.isReady());
    }

    /** Redis 失败时不得继续报告就绪。 */
    @Test
    void reportsUnavailableWhenRedisFails() {
        when(redis.getConnectionFactory()).thenThrow(new IllegalStateException("down"));

        assertFalse(service.isReady());
    }

    /** Worker 失败时不得继续报告就绪。 */
    @Test
    void reportsUnavailableWhenWorkerFails() {
        when(worker.get()).thenThrow(new IllegalStateException("down"));

        assertFalse(service.isReady());
    }
}
