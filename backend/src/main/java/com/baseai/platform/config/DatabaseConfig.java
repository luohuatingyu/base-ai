package com.baseai.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(PlatformProperties.class)
public class DatabaseConfig {

    /** 创建承载系统表、权限和日志的 MySQL 主数据源。 */
    @Bean
    @Primary
    public DataSource dataSource(PlatformProperties properties) {
        return createDataSource("system-mysql", properties.getSystemDatabase());
    }

    /** 暴露系统库 JDBC 入口供任务和日志批量操作使用。 */
    @Bean("systemJdbcTemplate")
    public JdbcTemplate systemJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 创建从属业务 PostgreSQL 数据源，业务模块不得访问系统库。 */
    @Bean("businessDataSource")
    public DataSource businessDataSource(PlatformProperties properties) {
        return createDataSource("business-postgres", properties.getBusinessDatabase());
    }

    /** 暴露 PostgreSQL 业务库 JDBC 入口供后续业务模块使用。 */
    @Bean("businessJdbcTemplate")
    public JdbcTemplate businessJdbcTemplate(@Qualifier("businessDataSource") DataSource dataSource) {
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
        return dataSource;
    }
}
