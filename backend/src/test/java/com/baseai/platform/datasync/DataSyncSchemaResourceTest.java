package com.baseai.platform.datasync;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验数据同步和部署迁移保留关键约束与索引。 */
class DataSyncSchemaResourceTest {
    /** V26 必须创建计划、运行、服务器和部署记录，并保持连接外键。 */
    @Test
    void migrationContainsRequiredTablesAndConstraints() throws Exception {
        String sql = new ClassPathResource("db/migration/mysql/V26__add_data_sync_and_servers.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        for (String table : new String[]{"data_sync_plan", "data_sync_run", "data_sync_run_table", "managed_server", "deployment_run"}) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table));
        }
        assertTrue(sql.contains("fk_data_sync_plan_source"));
        assertTrue(sql.contains("fk_data_sync_plan_target"));
        assertTrue(sql.contains("config_encrypted LONGTEXT NOT NULL"));
        assertTrue(sql.contains("voided BIT(1) NOT NULL DEFAULT b'0'"));
        assertTrue(sql.contains("uk_deployment_run_active UNIQUE (server_id, active_slot)"));
    }
}
