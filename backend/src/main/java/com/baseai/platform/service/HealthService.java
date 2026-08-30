package com.baseai.platform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.BooleanSupplier;

/** 检查承载平台功能的数据库、缓存和 Worker 是否均可用。 */
@Service
public class HealthService {
    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private final JdbcTemplate mysql;
    private final JdbcTemplate postgresql;
    private final StringRedisTemplate redis;
    private final RestClient worker;

    public HealthService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
                         @Qualifier("postgresqlJdbcTemplate") @Nullable JdbcTemplate postgresql,
                         StringRedisTemplate redis,
                         @Qualifier("pythonWorkerHealthRestClient") RestClient worker) {
        this.mysql = mysql;
        this.postgresql = postgresql;
        this.redis = redis;
        this.worker = worker;
    }

    /** 所有关键依赖均响应时才报告就绪，异常细节仅保留在服务端。 */
    public boolean isReady() {
        return check("mysql", () -> databaseReady(mysql))
            && (postgresql == null || check("postgresql", () -> databaseReady(postgresql)))
            && check("redis", this::redisReady)
            && check("python-worker", this::workerReady);
    }

    /** 使用最小查询确认关系型数据库连接可用。 */
    private boolean databaseReady(JdbcTemplate template) {
        return Integer.valueOf(1).equals(template.queryForObject("SELECT 1", Integer.class));
    }

    /** 使用 PING 确认 Redis 连接可用并及时释放连接。 */
    private boolean redisReady() {
        RedisConnectionFactory factory = redis.getConnectionFactory();
        if (factory == null) return false;
        try (RedisConnection connection = factory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        }
    }

    /** 调用 Worker 存活端点确认内部服务可用。 */
    @SuppressWarnings("unchecked")
    private boolean workerReady() {
        Map<String, Object> body = worker.get().uri("/health").retrieve().body(Map.class);
        return body != null && "UP".equals(body.get("status"));
    }

    /** 记录不可用组件和异常类型，但不记录连接信息或异常正文。 */
    private boolean check(String component, BooleanSupplier operation) {
        try {
            boolean ready = operation.getAsBoolean();
            if (!ready) log.warn("event=readiness_check_failed component={} error_type=unavailable", component);
            return ready;
        } catch (RuntimeException exception) {
            log.warn("event=readiness_check_failed component={} error_type={}", component,
                exception.getClass().getSimpleName());
            return false;
        }
    }
}
