package com.baseai.platform.datasource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSourcePermissionMigrationTest {
    /** V29 必须原位迁移连接维护权限并保留已有角色授权。 */
    @Test
    void movesConnectionPermissionsToDataSourceDomain() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:data-source-permission-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        insertMenu(jdbc, 1, null, "运维管理", "CATALOG", "/operations", null, "operations:catalog", 30);
        insertMenu(jdbc, 10, null, "工作流", "CATALOG", "/workflow", null, "automation:workflow:catalog", 20);
        insertMenu(jdbc, 11, 10L, "连接配置", "MENU", "/workflow/connections", "WorkflowConnectionsView",
            "automation:workflow:connection:list", 22);
        insertMenu(jdbc, 12, 11L, "新增连接", "BUTTON", null, null,
            "automation:workflow:connection:create", 221);
        insertMenu(jdbc, 13, 11L, "更新连接", "BUTTON", null, null,
            "automation:workflow:connection:update", 222);
        insertMenu(jdbc, 14, 11L, "删除连接", "BUTTON", null, null,
            "automation:workflow:connection:delete", 223);
        insertMenu(jdbc, 20, 1L, "数据同步", "MENU", "/data-sync", "DataSyncView",
            "operations:data-sync:list", 11);
        insertMenu(jdbc, 30, 1L, "服务器管理", "MENU", "/servers", "ServersView",
            "operations:server:list", 12);
        insertMenu(jdbc, 40, 1L, "设备 Agent", "MENU", "/automation/device-agents", "DeviceAgentsView",
            "operations:device-agent:list", 13);
        jdbc.update("INSERT INTO sys_role_menu VALUES (7,11),(7,13)");

        executeMigration(dataSource);

        assertEquals(Map.of(
            11L, "operations:data-source:list",
            12L, "operations:data-source:create",
            13L, "operations:data-source:update",
            14L, "operations:data-source:delete"
        ), jdbc.query("SELECT id,permission FROM sys_menu WHERE id BETWEEN 11 AND 14", resultSet -> {
            Map<Long, String> values = new java.util.LinkedHashMap<>();
            while (resultSet.next()) values.put(resultSet.getLong("id"), resultSet.getString("permission"));
            return values;
        }));
        assertEquals(Set.of("operations:catalog", "operations:data-source:list",
            "operations:data-source:update"), Set.copyOf(jdbc.queryForList("""
            SELECT menu.permission FROM sys_role_menu role_menu
            JOIN sys_menu menu ON menu.id=role_menu.menu_id WHERE role_menu.role_id=7
            """, String.class)));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_menu WHERE permission='operations:data-source:test'", Integer.class));
        assertEquals(ListView.expected(), jdbc.queryForObject("""
            SELECT CONCAT(name,'|',path,'|',component,'|',parent_id,'|',sort_order)
            FROM sys_menu WHERE id=11
            """, String.class));
        assertEquals(12, jdbc.queryForObject("SELECT sort_order FROM sys_menu WHERE id=20", Integer.class));
        assertEquals(13, jdbc.queryForObject("SELECT sort_order FROM sys_menu WHERE id=30", Integer.class));
        assertEquals(14, jdbc.queryForObject("SELECT sort_order FROM sys_menu WHERE id=40", Integer.class));
    }

    /** 创建权限迁移所需的最小菜单和角色关系结构。 */
    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE sys_menu(
                id BIGINT AUTO_INCREMENT PRIMARY KEY,parent_id BIGINT,name VARCHAR(100),type VARCHAR(20),path VARCHAR(160),
                component VARCHAR(120),icon VARCHAR(60),permission VARCHAR(120) UNIQUE,sort_order INT,
                visible BOOLEAN,enabled BOOLEAN,created_at TIMESTAMP,updated_at TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE sys_role_menu(
                role_id BIGINT NOT NULL,menu_id BIGINT NOT NULL,PRIMARY KEY(role_id,menu_id)
            )
            """);
    }

    /** 插入固定 ID 菜单以验证原位迁移和页面移动。 */
    private static void insertMenu(JdbcTemplate jdbc, long id, Long parentId, String name, String type,
                                   String path, String component, String permission, int sortOrder) {
        jdbc.update("""
            INSERT INTO sys_menu VALUES (?,?,?,?,?,?,NULL,?,?,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, id, parentId, name, type, path, component, permission, sortOrder);
    }

    /** 执行待验证的 V29 迁移。 */
    private static void executeMigration(DriverManagerDataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(
            "db/migration/mysql/V29__move_connections_to_data_sources.sql")).execute(dataSource);
    }

    /** 数据源列表页迁移后的稳定展示属性。 */
    private static final class ListView {
        /** 返回页面名称、路径、组件、父级和顺序的期望串。 */
        private static String expected() { return "数据源管理|/data-sources|DataSourcesView|1|11"; }
    }
}
