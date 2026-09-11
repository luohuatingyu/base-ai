package com.baseai.platform.deviceagent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证设备 Agent 菜单和权限完整迁移到自动化领域。 */
class DeviceAgentPermissionMigrationTest {
    /** 迁移必须原位更新权限与父级，并为既有角色补齐自动化目录权限。 */
    @Test
    void movesDeviceAgentPermissionsIntoAutomationDomain() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:device-agent-permission-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        insertMenu(jdbc, 2, null, "自动化", "CATALOG", "/automation", null,
            "automation:catalog", 40);
        insertMenu(jdbc, 3, null, "运维管理", "CATALOG", "/operations", null,
            "operations:catalog", 20);
        insertMenu(jdbc, 151, 3L, "设备 Agent 管理", "MENU", "/automation/device-agents",
            "DeviceAgentsView", "operations:device-agent:list", 14);
        insertMenu(jdbc, 152, 151L, "新增设备 Agent", "BUTTON", null, null,
            "operations:device-agent:create", 141);
        insertMenu(jdbc, 153, 151L, "修改设备 Agent", "BUTTON", null, null,
            "operations:device-agent:update", 142);
        insertMenu(jdbc, 154, 151L, "删除设备 Agent", "BUTTON", null, null,
            "operations:device-agent:delete", 143);
        insertMenu(jdbc, 155, 151L, "执行设备自动化", "BUTTON", null, null,
            "operations:device-agent:execute", 144);
        jdbc.update("INSERT INTO sys_role_menu VALUES (7,151),(7,155),(8,2),(8,151)");

        executeMigration(dataSource);

        assertEquals(Map.of(
            151L, "automation:device-agent:list",
            152L, "automation:device-agent:create",
            153L, "automation:device-agent:update",
            154L, "automation:device-agent:delete",
            155L, "automation:device-agent:execute"
        ), jdbc.query("SELECT id,permission FROM sys_menu WHERE id BETWEEN 151 AND 155", resultSet -> {
            Map<Long, String> permissions = new LinkedHashMap<>();
            while (resultSet.next()) permissions.put(resultSet.getLong("id"), resultSet.getString("permission"));
            return permissions;
        }));
        assertEquals("2|13", jdbc.queryForObject("""
            SELECT CONCAT(parent_id,'|',sort_order) FROM sys_menu WHERE id=151
            """, String.class));
        assertEquals(Set.of("automation:catalog", "automation:device-agent:list",
            "automation:device-agent:execute"), permissionsForRole(jdbc, 7));
        assertEquals(Set.of("automation:catalog", "automation:device-agent:list"), permissionsForRole(jdbc, 8));
        assertEquals(5, jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role_menu WHERE role_id IN (7,8)", Integer.class));
    }

    /** 创建权限迁移所需的最小菜单和角色关系结构。 */
    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE sys_menu(
                id BIGINT AUTO_INCREMENT PRIMARY KEY,parent_id BIGINT,name VARCHAR(100),type VARCHAR(20),
                path VARCHAR(160),component VARCHAR(120),icon VARCHAR(60),permission VARCHAR(120) UNIQUE,
                sort_order INT,visible BOOLEAN,enabled BOOLEAN,created_at TIMESTAMP,updated_at TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE sys_role_menu(
                role_id BIGINT NOT NULL,menu_id BIGINT NOT NULL,PRIMARY KEY(role_id,menu_id)
            )
            """);
    }

    /** 插入固定 ID 菜单以验证原位迁移行为。 */
    private static void insertMenu(JdbcTemplate jdbc, long id, Long parentId, String name, String type,
                                   String path, String component, String permission, int sortOrder) {
        jdbc.update("""
            INSERT INTO sys_menu VALUES (?,?,?,?,?,?,NULL,?,?,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, id, parentId, name, type, path, component, permission, sortOrder);
    }

    /** 执行设备 Agent 权限领域迁移。 */
    private static void executeMigration(DriverManagerDataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(
            "db/migration/mysql/V34__move_device_agents_to_automation.sql")).execute(dataSource);
    }

    /** 查询角色迁移后的权限集合。 */
    private static Set<String> permissionsForRole(JdbcTemplate jdbc, long roleId) {
        return Set.copyOf(jdbc.queryForList("""
            SELECT menu.permission FROM sys_role_menu role_menu
            JOIN sys_menu menu ON menu.id=role_menu.menu_id WHERE role_menu.role_id=?
            """, String.class, roleId));
    }
}
