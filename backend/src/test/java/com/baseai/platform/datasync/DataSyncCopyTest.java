package com.baseai.platform.datasync;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.service.TaskTraceService;
import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 使用真实 JDBC 连接验证批量建表、更新插入、全量替换和事务回滚。 */
class DataSyncCopyTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DataSyncService service;
    private JdbcDataSource sourceDataSource;
    private JdbcDataSource targetDataSource;
    private WorkflowConnectionService.StoredConnection sourceConfig;
    private WorkflowConnectionService.StoredConnection targetConfig;

    /** 创建 MySQL 兼容 H2 源库和目标库。 */
    @BeforeEach
    void setUp() throws Exception {
        sourceDataSource = dataSource("source");
        targetDataSource = dataSource("target");
        new JdbcTemplate(sourceDataSource).execute("CREATE TABLE orders(id BIGINT PRIMARY KEY,name VARCHAR(80),amount DECIMAL(12,2))");
        new JdbcTemplate(sourceDataSource).update("INSERT INTO orders VALUES (1,'first',10.25),(2,'second',20.50)");
        sourceConfig = connection(1L, false);
        targetConfig = connection(2L, true);
        service = new DataSyncService(Mockito.mock(JdbcTemplate.class), Mockito.mock(WorkflowConnectionService.class),
            objectMapper, Mockito.mock(StringRedisTemplate.class), Mockito.mock(ThreadPoolTaskExecutor.class),
            Mockito.mock(TaskTraceService.class));
    }

    /** UPSERT 应创建缺失表并根据主键更新已有行。 */
    @Test
    void createsTableAndUpsertsRows() throws Exception {
        try (Connection source = sourceDataSource.getConnection(); Connection target = targetDataSource.getConnection()) {
            DataSyncService.TableResult first = service.copyTable(source, target, sourceConfig, targetConfig, mapping("orders"), "UPSERT");
            assertEquals(2, first.readRows());
            assertEquals("first", query(targetDataSource, "SELECT name FROM orders WHERE id=1"));
            new JdbcTemplate(sourceDataSource).update("UPDATE orders SET name='updated' WHERE id=1");
            service.copyTable(source, target, sourceConfig, targetConfig, mapping("orders"), "UPSERT");
            assertEquals("updated", query(targetDataSource, "SELECT name FROM orders WHERE id=1"));
            assertEquals(2, new JdbcTemplate(targetDataSource).queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        }
    }

    /** 全量替换应删除目标端不再存在的行。 */
    @Test
    void fullyReplacesRows() throws Exception {
        try (Connection source = sourceDataSource.getConnection(); Connection target = targetDataSource.getConnection()) {
            service.copyTable(source, target, sourceConfig, targetConfig, mapping("orders"), "UPSERT");
            new JdbcTemplate(sourceDataSource).update("DELETE FROM orders WHERE id=2");
            service.copyTable(source, target, sourceConfig, targetConfig, mapping("orders"), "FULL_REPLACE");
            assertEquals(1, new JdbcTemplate(targetDataSource).queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        }
    }

    /** 全量替换写入失败时必须回滚清空动作并保留旧数据。 */
    @Test
    void rollsBackFailedFullReplace() throws Exception {
        JdbcTemplate targetTemplate = new JdbcTemplate(targetDataSource);
        targetTemplate.execute("CREATE TABLE guarded(id BIGINT PRIMARY KEY,name VARCHAR(80),amount DECIMAL(12,2),CONSTRAINT chk_name CHECK(name <> 'second'))");
        targetTemplate.update("INSERT INTO guarded VALUES (99,'old',1.00)");
        DataSyncModels.TableMapping mapping = new DataSyncModels.TableMapping(null, "orders", null, "guarded", List.of());
        try (Connection source = sourceDataSource.getConnection(); Connection target = targetDataSource.getConnection()) {
            assertThrows(SQLException.class, () -> service.copyTable(source, target, sourceConfig, targetConfig, mapping, "FULL_REPLACE"));
        }
        assertEquals(1, targetTemplate.queryForObject("SELECT COUNT(*) FROM guarded", Integer.class));
        assertEquals("old", query(targetDataSource, "SELECT name FROM guarded WHERE id=99"));
    }

    /** 预检必须在清空目标表前拒绝可能截断的字段类型。 */
    @Test
    void rejectsNarrowTargetBeforeFullReplace() throws Exception {
        JdbcTemplate targetTemplate = new JdbcTemplate(targetDataSource);
        targetTemplate.execute("CREATE TABLE narrow_orders(id BIGINT PRIMARY KEY,name VARCHAR(3))");
        targetTemplate.update("INSERT INTO narrow_orders VALUES (99,'old')");
        DataSyncModels.TableMapping mapping = new DataSyncModels.TableMapping(null, "orders", null, "narrow_orders", List.of("id", "name"));
        try (Connection source = sourceDataSource.getConnection(); Connection target = targetDataSource.getConnection()) {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service.copyTable(source, target, sourceConfig, targetConfig, mapping, "FULL_REPLACE"));
            assertEquals("dataSync.columnTypeMismatch", exception.getMessageKey());
        }
        assertEquals("old", query(targetDataSource, "SELECT name FROM narrow_orders WHERE id=99"));
    }

    /** UPSERT 配置排除主键时必须在写入前失败。 */
    @Test
    void rejectsUpsertWithoutSelectedPrimaryKey() throws Exception {
        JdbcTemplate targetTemplate = new JdbcTemplate(targetDataSource);
        targetTemplate.execute("CREATE TABLE partial_orders(id BIGINT PRIMARY KEY,name VARCHAR(80))");
        DataSyncModels.TableMapping mapping = new DataSyncModels.TableMapping(null, "orders", null, "partial_orders", List.of("name"));
        try (Connection source = sourceDataSource.getConnection(); Connection target = targetDataSource.getConnection()) {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service.copyTable(source, target, sourceConfig, targetConfig, mapping, "UPSERT"));
            assertEquals("dataSync.primaryKeyRequired", exception.getMessageKey());
        }
    }

    /** APPEND 应支持没有主键的普通事件表。 */
    @Test
    void appendsRowsWithoutPrimaryKey() throws Exception {
        JdbcTemplate sourceTemplate = new JdbcTemplate(sourceDataSource);
        sourceTemplate.execute("CREATE TABLE events(message VARCHAR(80))");
        sourceTemplate.update("INSERT INTO events VALUES ('one'),('two')");
        try (Connection source = sourceDataSource.getConnection(); Connection target = targetDataSource.getConnection()) {
            service.copyTable(source, target, sourceConfig, targetConfig, mapping("events"), "APPEND");
        }
        assertEquals(2, new JdbcTemplate(targetDataSource).queryForObject("SELECT COUNT(*) FROM events", Integer.class));
    }

    /** 无法跨库安全映射的专有 JDBC 类型必须在建表前失败。 */
    @Test
    void rejectsUnsupportedColumnType() throws Exception {
        new JdbcTemplate(sourceDataSource).execute("CREATE TABLE exotic(id BIGINT PRIMARY KEY,payload JAVA_OBJECT)");
        try (Connection source = sourceDataSource.getConnection(); Connection target = targetDataSource.getConnection()) {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service.copyTable(source, target, sourceConfig, targetConfig, mapping("exotic"), "APPEND"));
            assertEquals("dataSync.unsupportedColumnType", exception.getMessageKey());
        }
    }

    /** MySQL 目标不允许通过截断精度的方式创建十进制字段。 */
    @Test
    void rejectsDecimalThatWouldBeNarrowedForMySql() throws Exception {
        new JdbcTemplate(sourceDataSource).execute("CREATE TABLE precise_amounts(id BIGINT PRIMARY KEY,amount DECIMAL(70,35))");
        try (Connection source = sourceDataSource.getConnection(); Connection target = targetDataSource.getConnection()) {
            BusinessException exception = assertThrows(BusinessException.class,
                () -> service.copyTable(source, target, sourceConfig, targetConfig, mapping("precise_amounts"), "APPEND"));
            assertEquals("dataSync.unsupportedColumnType", exception.getMessageKey());
        }
    }

    /** PostgreSQL 方言应能创建缺失表并执行追加同步。 */
    @Test
    void copiesRowsWithPostgresqlDialect() throws Exception {
        JdbcDataSource postgresSource = dataSource("postgres-source", "PostgreSQL");
        JdbcDataSource postgresTarget = dataSource("postgres-target", "PostgreSQL");
        JdbcTemplate sourceTemplate = new JdbcTemplate(postgresSource);
        sourceTemplate.execute("CREATE TABLE events(id BIGINT,message VARCHAR(80))");
        sourceTemplate.update("INSERT INTO events VALUES (1,'created')");
        try (Connection source = postgresSource.getConnection(); Connection target = postgresTarget.getConnection()) {
            DataSyncService.TableResult result = service.copyTable(source, target,
                connection(3L, false, "POSTGRESQL"), connection(4L, true, "POSTGRESQL"),
                mapping("events"), "APPEND");
            assertEquals(1, result.writtenRows());
        }
        assertEquals("created", query(postgresTarget, "SELECT message FROM events WHERE id=1"));
    }

    /** 创建隔离的 H2 数据源。 */
    private JdbcDataSource dataSource(String name) {
        return dataSource(name, "MySQL");
    }

    /** 创建指定兼容方言的隔离 H2 数据源。 */
    private JdbcDataSource dataSource(String name, String mode) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:data-sync-" + name + "-" + System.nanoTime()
            + ";MODE=" + mode + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    /** 创建仅供复制器读取方言和写权限的连接配置。 */
    private WorkflowConnectionService.StoredConnection connection(Long id, boolean allowWrite) throws Exception {
        return connection(id, allowWrite, "MYSQL");
    }

    /** 创建指定数据库类型的测试连接配置。 */
    private WorkflowConnectionService.StoredConnection connection(Long id, boolean allowWrite, String type) throws Exception {
        String url = "POSTGRESQL".equals(type) ? "jdbc:postgresql://unused" : "jdbc:mysql://unused";
        return new WorkflowConnectionService.StoredConnection(id, "DB" + id, "Database", type,
            objectMapper.readTree("{\"url\":\"" + url + "\",\"allowWrite\":" + allowWrite + "}"),
            7L, true, null, null);
    }

    /** 创建同名源目标表映射。 */
    private DataSyncModels.TableMapping mapping(String table) { return new DataSyncModels.TableMapping(null, table, null, table, List.of()); }
    /** 查询单个字符串结果。 */
    private String query(JdbcDataSource dataSource, String sql) { return new JdbcTemplate(dataSource).queryForObject(sql, String.class); }
}
