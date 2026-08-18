package com.baseai.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 数据库访问组件配置。
 *
 * <p>根据平台配置创建不同用途的数据源和 JDBC 模板，并通过限定名称隔离访问边界。</p>
 */
@Configuration
@EnableConfigurationProperties(PlatformProperties.class)
public class DatabaseConfig {

    /** 创建承载系统表、权限和日志的 MySQL 主数据源。 */
    @Bean("mysqlDataSource")
    @Primary
    public DataSource mysqlDataSource(PlatformProperties properties) {
        HikariDataSource dataSource = createDataSource("mysql", properties.getMysqlDatabase());
        migrate(dataSource, "classpath:db/migration/mysql");
        return dataSource;
    }

    /** 按数据库类型暴露 MySQL JDBC 入口。 */
    @Bean("mysqlJdbcTemplate")
    public JdbcTemplate mysqlJdbcTemplate(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 创建与权限主链路隔离的 MySQL 日志连接池。 */
    @Bean("auditDataSource")
    public DataSource auditDataSource(PlatformProperties properties) {
        HikariDataSource dataSource = createDataSource("mysql-audit", properties.getMysqlDatabase());
        dataSource.setMaximumPoolSize(Math.max(2, Math.min(10, properties.getMysqlDatabase().getMaximumPoolSize() / 2)));
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    /** 暴露独立日志 JDBC 入口，避免占用权限业务连接。 */
    @Bean("auditJdbcTemplate")
    public JdbcTemplate auditJdbcTemplate(@Qualifier("auditDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 创建从属业务 PostgreSQL 数据源，业务模块不得访问系统库。 */
    @Bean("postgresqlDataSource")
    public DataSource postgresqlDataSource(PlatformProperties properties) {
        HikariDataSource dataSource = createDataSource("postgresql", properties.getPostgresqlDatabase());
        migrate(dataSource, "classpath:db/migration/postgresql");
        return dataSource;
    }

    /** 按数据库类型暴露 PostgreSQL JDBC 入口。 */
    @Bean("postgresqlJdbcTemplate")
    public JdbcTemplate postgresqlJdbcTemplate(@Qualifier("postgresqlDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 根据统一属性创建带连接池的数据源。 */
    private HikariDataSource createDataSource(String poolName, DatabaseProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName(poolName);
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setMaximumPoolSize(properties.getMaximumPoolSize());
        dataSource.setConnectionTimeout(properties.getConnectionTimeoutMs());
        dataSource.setMinimumIdle(Math.min(2, properties.getMaximumPoolSize()));
        return dataSource;
    }

    /** 在数据源暴露给 JPA 或业务 JDBC 前执行对应数据库的版本化迁移。 */
    private void migrate(HikariDataSource dataSource, String location) {
        try {
            Flyway.configure().dataSource(dataSource).locations(location)
                .baselineOnMigrate(true).baselineVersion("0").validateMigrationNaming(true)
                .load().migrate();
        } catch (RuntimeException exception) {
            dataSource.close();
            throw exception;
        }
    }
}
