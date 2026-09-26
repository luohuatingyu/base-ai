package com.baseai.platform.deployment;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.boot.convert.DurationStyle;

import java.io.PrintStream;
import java.net.UnknownHostException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/** 在 Spring 和 Flyway 启动前验证容器网络中的真实依赖，不修改数据库内容。 */
public final class DeploymentPreflight {
    /** 工具入口不允许实例化。 */
    private DeploymentPreflight() {}

    record Database(String url, String username, String password) {}
    record Settings(Database mysql, Database migration, RedisURI redis) {}
    @FunctionalInterface
    interface Check { void run() throws Exception; }

    /** 任一依赖失败即返回非零退出码，诊断不输出 URL、密码或驱动异常正文。 */
    public static void main(String[] args) {
        int result = 1;
        try {
            Settings config = settings(System.getenv());
            boolean ok = check("mysql", () -> database(config.mysql()), System.out, 15000);
            ok &= check("mysql-migration", () -> database(config.migration()), System.out, 15000);
            ok &= check("redis", () -> redis(config.redis()), System.out, 15000);
            if (Boolean.parseBoolean(System.getenv().getOrDefault("POSTGRES_ENABLED", "false"))) {
                Database postgres = new Database(required(System.getenv(), "POSTGRES_URL"),
                    required(System.getenv(), "POSTGRES_USERNAME"), required(System.getenv(), "POSTGRES_PASSWORD"));
                ok &= check("postgresql", () -> database(postgres), System.out, 15000);
            }
            result = ok ? 0 : 1;
        } catch (IllegalArgumentException exception) {
            System.out.println("preflight component=configuration status=failed reason=invalid_configuration");
        }
        System.exit(result);
    }

    /** 保持 Compose 的默认配置及迁移账号回退语义，严格拒绝无效参数。 */
    static Settings settings(Map<String, String> env) {
        Database mysql = new Database(required(env, "MYSQL_URL"), required(env, "MYSQL_USERNAME"), required(env, "MYSQL_PASSWORD"));
        Database migration = new Database(value(env, "MYSQL_MIGRATION_URL", mysql.url()),
            value(env, "MYSQL_MIGRATION_USERNAME", mysql.username()), value(env, "MYSQL_MIGRATION_PASSWORD", mysql.password()));
        if (!mysql.url().startsWith("jdbc:mysql://") || !migration.url().startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("MYSQL_URL");
        }
        int port = Integer.parseInt(value(env, "REDIS_PORT", "6379"));
        int database = Integer.parseInt(value(env, "REDIS_DATABASE", "0"));
        String ssl = value(env, "REDIS_SSL", "true");
        Duration timeout = DurationStyle.detectAndParse(value(env, "REDIS_TIMEOUT", "5s"));
        if (port < 1 || port > 65535 || database < 0 || timeout.isZero() || timeout.isNegative()
            || !("true".equalsIgnoreCase(ssl) || "false".equalsIgnoreCase(ssl))) {
            throw new IllegalArgumentException("REDIS_CONFIGURATION");
        }
        RedisURI.Builder redis = RedisURI.builder().withHost(required(env, "REDIS_HOST")).withPort(port)
            .withDatabase(database).withSsl(Boolean.parseBoolean(ssl)).withTimeout(timeout);
        String password = required(env, "REDIS_PASSWORD");
        String username = value(env, "REDIS_USERNAME", "");
        if (username.isBlank()) redis.withPassword(password.toCharArray());
        else redis.withAuthentication(username, password.toCharArray());
        return new Settings(mysql, migration, redis.build());
    }

    /** 空迁移变量与 Compose 的 :- 展开一致，回退原有连接配置。 */
    private static String value(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** 必填值只校验而不打印，保留密码中的合法空格。 */
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key);
        return value;
    }

    /** 真实连接目标数据库并执行只读查询；JDBC URL 原样保留 TLS 配置。 */
    static void database(Database target) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", target.username());
        properties.setProperty("password", target.password());
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "5000");
        try (var connection = DriverManager.getConnection(target.url(), properties);
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery("SELECT 1")) {
                if (!result.next() || result.getInt(1) != 1) throw new SQLException("Unexpected readiness result");
            }
        }
    }

    /** Redis 使用与应用相同的 ACL、TLS、库号及超时执行 PING，并释放连接。 */
    private static void redis(RedisURI uri) {
        RedisClient client = RedisClient.create(uri);
        try (var connection = client.connect()) {
            if (!"PONG".equals(connection.sync().ping())) throw new IllegalStateException("Unexpected readiness result");
        } finally {
            client.shutdown();
        }
    }

    /** 为 DNS、连接及驱动清理设定总截止时间，失败不会无限拖住部署。 */
    static boolean check(String component, Check operation, PrintStream output, long timeoutMillis) {
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "deployment-preflight");
            thread.setDaemon(true);
            return thread;
        });
        var future = executor.submit(() -> { operation.run(); return null; });
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            output.println("preflight component=" + component + " status=ok");
            return true;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            output.println("preflight component=" + component + " status=failed reason=" + reason(exception));
            return false;
        } finally {
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    /** 仅返回有限错误分类，避免驱动异常正文暴露连接参数。 */
    private static String reason(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) return "dns";
            if (cause instanceof SSLException) return "tls";
            if (cause instanceof TimeoutException || cause instanceof java.net.SocketTimeoutException
                || cause.getClass().getSimpleName().contains("Timeout")) return "timeout";
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("28")) return "authentication";
            if (cause instanceof io.lettuce.core.RedisCommandExecutionException) return "authentication_or_database";
        }
        return "unavailable";
    }
}
