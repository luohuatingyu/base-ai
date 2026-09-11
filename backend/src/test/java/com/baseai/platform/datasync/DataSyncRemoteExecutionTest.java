package com.baseai.platform.datasync;

import com.baseai.platform.workflow.WorkflowConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 使用纯 JDBC Worker 入口验证远程预检、复制和累计进度。 */
class DataSyncRemoteExecutionTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 远程 Worker 必须在写入前预检并输出每张表及最终进度。 */
    @Test
    void previewsAndCopiesWithProgress() throws Exception {
        JdbcDataSource sourceDataSource = dataSource("remote-source");
        JdbcDataSource targetDataSource = dataSource("remote-target");
        JdbcTemplate source = new JdbcTemplate(sourceDataSource);
        source.execute("CREATE TABLE orders(id BIGINT PRIMARY KEY,name VARCHAR(80))");
        source.update("INSERT INTO orders VALUES (1,'remote')");
        WorkflowConnectionService.StoredConnection sourceConfig = connection(1L, sourceDataSource, false);
        WorkflowConnectionService.StoredConnection targetConfig = connection(2L, targetDataSource, true);
        DataSyncService engine = DataSyncService.workerEngine(objectMapper);
        List<DataSyncModels.TableMapping> mappings = List.of(
            new DataSyncModels.TableMapping("", "orders", "", "orders", List.of()));

        List<DataSyncService.WorkerProgress> progress = new ArrayList<>();
        DataSyncModels.PreviewView preview;
        try (Connection sourceJdbc = sourceDataSource.getConnection(); Connection targetJdbc = targetDataSource.getConnection()) {
            preview = engine.workerPreview(sourceJdbc, targetJdbc, sourceConfig, targetConfig, "UPSERT", mappings);
            engine.workerRun(sourceJdbc, targetJdbc, sourceConfig, targetConfig, "UPSERT", mappings, progress::add);
        }

        assertEquals(1, preview.tables().get(0).sourceRows());
        assertEquals("SUCCESS", progress.get(progress.size() - 1).status());
        assertEquals(1, progress.get(progress.size() - 1).writtenRows());
        assertEquals("remote", new JdbcTemplate(targetDataSource)
            .queryForObject("SELECT name FROM orders WHERE id=1", String.class));
    }

    /** 创建可被 DriverManager 再次打开的内存数据库。 */
    private JdbcDataSource dataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    /** 将 H2 URL 包装为 MySQL 方言配置以复用现有复制引擎。 */
    private WorkflowConnectionService.StoredConnection connection(Long id, JdbcDataSource dataSource,
                                                                   boolean allowWrite) throws Exception {
        return new WorkflowConnectionService.StoredConnection(id, "DB" + id, "Database", "MYSQL",
            objectMapper.readTree("{\"url\":\"" + dataSource.getURL() + "\",\"allowWrite\":" + allowWrite + "}"),
            7L, true, null, null);
    }
}
