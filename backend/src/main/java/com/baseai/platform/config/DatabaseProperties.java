package com.baseai.platform.config;

/**
 * 单个关系型数据源的连接属性。
 *
 * <p>该对象被 MySQL 和 PostgreSQL 配置复用，避免重复定义连接字段。</p>
 */
public class DatabaseProperties {
    /** JDBC 连接地址。 */
    private String url;
    /** 数据库用户名。 */
    private String username;
    /** 数据库密码。 */
    private String password;
    /** JDBC 驱动类名。 */
    private String driverClassName;
    /** 连接池最大连接数。 */
    private int maximumPoolSize = 10;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
}
