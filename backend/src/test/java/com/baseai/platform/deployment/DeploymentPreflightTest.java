package com.baseai.platform.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.*;

/** 部署检查必须在启动前拒绝错误配置，并且不泄露连接凭据。 */
class DeploymentPreflightTest {
    /** 提供独立的有效输入，避免从实现反推期望。 */
    private Map<String, String> environment() {
        return new HashMap<>(Map.of("MYSQL_URL", "jdbc:mysql://mysql:3306/app?sslMode=VERIFY_IDENTITY",
            "MYSQL_USERNAME", "app", "MYSQL_PASSWORD", "mysql-secret", "REDIS_HOST", "redis",
            "REDIS_PASSWORD", "redis-secret"));
    }

    /** 缺失或空白的关键配置必须阻止启动。 */
    @ParameterizedTest
    @ValueSource(strings = {"MYSQL_URL", "MYSQL_USERNAME", "MYSQL_PASSWORD", "REDIS_HOST", "REDIS_PASSWORD"})
    void rejectsMissingConfiguration(String key) {
        var env = environment();
        env.remove(key);
        assertThrows(IllegalArgumentException.class, () -> DeploymentPreflight.settings(env));
        env.put(key, "  ");
        assertThrows(IllegalArgumentException.class, () -> DeploymentPreflight.settings(env));
    }

    /** 默认迁移账号回退运行账号，Redis 默认启用 TLS。 */
    @Test
    void preservesSecureDefaultsAndMigrationFallback() {
        var settings = DeploymentPreflight.settings(environment());
        assertEquals(settings.mysql(), settings.migration());
        assertTrue(settings.redis().isSsl());
        assertEquals(6379, settings.redis().getPort());
        assertEquals(0, settings.redis().getDatabase());
        assertEquals(Duration.ofSeconds(5), settings.redis().getTimeout());
    }

    /** 分离的迁移凭据及 Redis ACL、库号、超时必须准确传给驱动。 */
    @Test
    void honorsSeparateMigrationAndRedisSettings() {
        var env = environment();
        env.putAll(Map.of("MYSQL_MIGRATION_URL", "jdbc:mysql://migration:3307/app",
            "MYSQL_MIGRATION_USERNAME", "migrator", "MYSQL_MIGRATION_PASSWORD", "ddl-secret",
            "REDIS_USERNAME", "cache-user", "REDIS_DATABASE", "2", "REDIS_PORT", "6380",
            "REDIS_SSL", "false", "REDIS_TIMEOUT", "1500ms"));
        var settings = DeploymentPreflight.settings(env);
        assertEquals("jdbc:mysql://migration:3307/app", settings.migration().url());
        assertEquals("migrator", settings.migration().username());
        assertEquals("ddl-secret", settings.migration().password());
        assertEquals("cache-user", settings.redis().getUsername());
        assertArrayEquals("redis-secret".toCharArray(), settings.redis().getPassword());
        assertEquals(2, settings.redis().getDatabase());
        assertEquals(6380, settings.redis().getPort());
        assertFalse(settings.redis().isSsl());
        assertEquals(Duration.ofMillis(1500), settings.redis().getTimeout());
    }

    /** 非法参数不得被默认值掩盖。 */
    @ParameterizedTest
    @ValueSource(strings = {"REDIS_PORT=0", "REDIS_PORT=65536", "REDIS_DATABASE=-1", "REDIS_SSL=tru",
        "REDIS_TIMEOUT=0s", "REDIS_TIMEOUT=-1s", "REDIS_TIMEOUT=no", "MYSQL_URL=jdbc:h2:mem:test"})
    void rejectsInvalidSettings(String input) {
        var env = environment();
        var pair = input.split("=", 2);
        env.put(pair[0], pair[1]);
        assertThrows(IllegalArgumentException.class, () -> DeploymentPreflight.settings(env));
    }

    /** 使用真实 JDBC 查询验证结果及连接关闭，不模拟核心数据库检查。 */
    @Test
    void checksDatabaseWithSelectOne() throws Exception {
        DeploymentPreflight.database(new DeploymentPreflight.Database("jdbc:h2:mem:preflight", "sa", ""));
        assertThrows(SQLException.class, () -> DeploymentPreflight.database(
            new DeploymentPreflight.Database("jdbc:missing:database", "sa", "secret")));
    }

    /** DNS、TLS、认证和超时错误分类不得携带驱动异常里的密码。 */
    @Test
    void reportsFailuresWithoutSecrets() {
        var output = new ByteArrayOutputStream();
        var printer = new PrintStream(output);
        assertFalse(DeploymentPreflight.check("mysql", () -> { throw new UnknownHostException("mysql-secret"); }, printer, 500));
        assertFalse(DeploymentPreflight.check("redis", () -> { throw new SSLHandshakeException("redis-secret"); }, printer, 500));
        assertFalse(DeploymentPreflight.check("mysql", () -> { throw new SQLException("ddl-secret", "28000"); }, printer, 500));
        assertFalse(DeploymentPreflight.check("redis", () -> { throw new TimeoutException("token-secret"); }, printer, 500));
        String text = output.toString();
        assertTrue(text.contains("dns"));
        assertTrue(text.contains("tls"));
        assertTrue(text.contains("authentication"));
        assertTrue(text.contains("timeout"));
        assertFalse(text.contains("secret"));
    }

    /** 依赖无响应也必须在有界时间内失败，不能无限等待 DNS 或驱动。 */
    @Test
    void boundsChecksAndReportsSuccess() {
        var output = new ByteArrayOutputStream();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFalse(
            DeploymentPreflight.check("mysql", () -> Thread.sleep(10000), new PrintStream(output), 25)));
        assertTrue(output.toString().contains("timeout"));
        assertTrue(DeploymentPreflight.check("mysql", () -> {}, new PrintStream(output), 500));
        assertTrue(output.toString().contains("status=ok"));
    }
}
