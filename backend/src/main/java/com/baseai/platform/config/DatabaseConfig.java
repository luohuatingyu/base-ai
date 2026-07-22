package com.baseai.platform.config;

import com.zaxxer.hikari.HikariDataSource;
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
        return createDataSource("mysql", properties.getMysqlDatabase());
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
        dataSource.setMaximumPoolSize(30);
        dataSource.setMinimumIdle(15);
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
        return createDataSource("postgresql", properties.getPostgresqlDatabase());
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
        dataSource.setMinimumIdle((properties.getMaximumPoolSize() + 1) / 2);
        return dataSource;
    }
}
