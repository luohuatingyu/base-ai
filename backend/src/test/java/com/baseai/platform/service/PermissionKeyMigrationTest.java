package com.baseai.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionKeyMigrationTest {
    /** V28 必须原位迁移资源 KEY，并只为存量角色补齐目录祖先。 */
    @Test
    void migratesKeysWithoutChangingResourceGrants() {
        DriverManagerDataSource dataSource = dataSource("permission-key-migration");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        seedLegacyMenus(jdbc);
        jdbc.update("INSERT INTO sys_role VALUES (1,'CUSTOM')");
        jdbc.update("INSERT INTO sys_role VALUES (2,'SYSTEM_CUSTOM')");
        jdbc.update("INSERT INTO sys_role_menu VALUES (1,101),(1,102),(1,111),(1,121),(1,131),(1,141),(1,151),(1,161)");
        jdbc.update("INSERT INTO sys_role_menu VALUES (2,171),(2,181),(2,191)");

        executeMigration(dataSource);

        assertEquals(Map.ofEntries(
            Map.entry(101L, "ai:model:model:list"),
            Map.entry(102L, "ai:model:model:update"),
            Map.entry(111L, "ai:model:knowledge-base:list"),
            Map.entry(121L, "automation:workflow:canvas:list"),
            Map.entry(131L, "operations:data-sync:list"),
            Map.entry(141L, "operations:server:list"),
            Map.entry(151L, "operations:device-agent:list"),
            Map.entry(161L, "operations:task:view"),
            Map.entry(171L, "system:user:list"),
            Map.entry(181L, "system:department:list"),
            Map.entry(191L, "system:mail:account:list")
        ), permissionsById(jdbc, Set.of(101L, 102L, 111L, 121L, 131L, 141L, 151L, 161L, 171L, 181L, 191L)));
        assertEquals(Set.of(
            "ai:catalog", "ai:model:catalog", "ai:model:model:list", "ai:model:model:update",
            "ai:model:knowledge-base:list",
            "automation:catalog", "automation:workflow:catalog", "automation:workflow:canvas:list",
            "operations:catalog", "operations:monitoring:catalog", "operations:data-sync:list",
            "operations:server:list", "operations:device-agent:list", "operations:task:view"
        ), rolePermissions(jdbc, 1L));
        assertEquals(Set.of(
            "system:catalog", "system:access:catalog", "system:user:list",
            "system:organization:catalog", "system:department:list",
            "system:mail:catalog", "system:mail:account:list"
        ), rolePermissions(jdbc, 2L));
        assertEquals(Long.valueOf(101L), jdbc.queryForObject(
            "SELECT parent_id FROM sys_menu WHERE id=102", Long.class));
        assertFalse(hasLegacyPermission(jdbc));
    }

    /** V28 重复执行不得创建重复目录或改变已有菜单 ID。 */
    @Test
    void remainsIdempotentAndPreservesCatalogIds() {
        DriverManagerDataSource dataSource = dataSource("permission-key-idempotency");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        seedLegacyMenus(jdbc);

        executeMigration(dataSource);
        Map<String, Long> firstIds = catalogIds(jdbc);
        executeMigration(dataSource);

        assertEquals(firstIds, catalogIds(jdbc));
        assertEquals(Long.valueOf(10L), firstIds.get("ai:model:catalog"));
        assertEquals(Long.valueOf(20L), firstIds.get("system:mail:catalog"));
        assertEquals(Long.valueOf(30L), firstIds.get("automation:workflow:catalog"));
        assertEquals(10, firstIds.size());
        assertEquals(10, jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_menu WHERE type='CATALOG'", Integer.class));
    }

    /** V28 必须能从权限前缀和目录插入已经部分完成的状态继续执行。 */
    @Test
    void resumesFromPartiallyAppliedState() {
        DriverManagerDataSource dataSource = dataSource("permission-key-partial-retry");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        seedLegacyMenus(jdbc);
        jdbc.update("UPDATE sys_menu SET permission='ai:model:catalog' WHERE permission='model:catalog'");
        jdbc.update("UPDATE sys_menu SET permission='ai:model:model:list' WHERE permission='model:model:list'");
        insertMenu(jdbc, 40, null, "运维管理", "CATALOG", "operations:catalog");

        executeMigration(dataSource);

        assertFalse(hasLegacyPermission(jdbc));
        assertEquals(Long.valueOf(10L), catalogIds(jdbc).get("ai:model:catalog"));
        assertEquals(Long.valueOf(40L), catalogIds(jdbc).get("operations:catalog"));
        assertEquals(10, catalogIds(jdbc).size());
    }

    /** 创建与权限迁移所需约束一致的隔离数据库。 */
    private static DriverManagerDataSource dataSource(String name) {
        return new DriverManagerDataSource(
            "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** 创建最小菜单和角色授权结构。 */
    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE sys_menu(
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                parent_id BIGINT,
                name VARCHAR(100) NOT NULL,
                type VARCHAR(20) NOT NULL,
                path VARCHAR(160),
                component VARCHAR(120),
                icon VARCHAR(60),
                permission VARCHAR(120) UNIQUE,
                sort_order INT NOT NULL,
                visible BOOLEAN NOT NULL,
                enabled BOOLEAN NOT NULL,
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL
            )
            """);
        jdbc.execute("CREATE TABLE sys_role(id BIGINT PRIMARY KEY,code VARCHAR(64) NOT NULL)");
        jdbc.execute("""
            CREATE TABLE sys_role_menu(
                role_id BIGINT NOT NULL,
                menu_id BIGINT NOT NULL,
                PRIMARY KEY(role_id,menu_id)
            )
            """);
    }

    /** 写入四个旧一级目录及各业务域代表性资源。 */
    private static void seedLegacyMenus(JdbcTemplate jdbc) {
        insertMenu(jdbc, 1, null, "AI 能力", "CATALOG", "ai:catalog");
        insertMenu(jdbc, 2, null, "系统管理", "CATALOG", "system:catalog");
        insertMenu(jdbc, 3, null, "自动化", "CATALOG", "automation:catalog");
        insertMenu(jdbc, 10, null, "模型管理", "CATALOG", "model:catalog");
        insertMenu(jdbc, 20, null, "邮件管理", "CATALOG", "mail:catalog");
        insertMenu(jdbc, 30, null, "工作流", "CATALOG", "workflow:catalog");
        insertMenu(jdbc, 101, 10L, "模型配置", "MENU", "model:model:list");
        insertMenu(jdbc, 102, 101L, "编辑模型配置", "BUTTON", "model:model:update");
        insertMenu(jdbc, 111, 10L, "知识库", "MENU", "knowledge:base:list");
        insertMenu(jdbc, 121, 30L, "画布管理", "MENU", "workflow:canvas:list");
        insertMenu(jdbc, 131, 2L, "数据同步", "MENU", "data-sync:list");
        insertMenu(jdbc, 141, 2L, "服务器管理", "MENU", "server:list");
        insertMenu(jdbc, 151, 3L, "设备 Agent 管理", "MENU", "automation:device-agent:list");
        insertMenu(jdbc, 161, 2L, "任务调度", "MENU", "system:task:view");
        insertMenu(jdbc, 171, 2L, "用户管理", "MENU", "system:user:list");
        insertMenu(jdbc, 181, 2L, "部门管理", "MENU", "system:department:list");
        insertMenu(jdbc, 191, 20L, "邮箱配置", "MENU", "mail:account:list");
    }

    /** 插入固定 ID 菜单，便于验证原位更新。 */
    private static void insertMenu(JdbcTemplate jdbc, long id, Long parentId, String name, String type,
                                   String permission) {
        jdbc.update("""
            INSERT INTO sys_menu(id,parent_id,name,type,path,component,icon,permission,sort_order,visible,enabled,
                                 created_at,updated_at)
            VALUES (?,?,?,?,NULL,NULL,NULL,?,10,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, id, parentId, name, type, permission);
    }

    /** 执行待验证的 V28 迁移。 */
    private static void executeMigration(DriverManagerDataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(
            "db/migration/mysql/V28__migrate_permission_keys.sql")).execute(dataSource);
    }

    /** 查询指定菜单 ID 对应的新权限 KEY。 */
    private static Map<Long, String> permissionsById(JdbcTemplate jdbc, Set<Long> ids) {
        return jdbc.query("SELECT id,permission FROM sys_menu", resultSet -> {
            Map<Long, String> values = new java.util.LinkedHashMap<>();
            while (resultSet.next()) {
                long id = resultSet.getLong("id");
                if (ids.contains(id)) values.put(id, resultSet.getString("permission"));
            }
            return values;
        });
    }

    /** 查询角色迁移后的完整授权集合。 */
    private static Set<String> rolePermissions(JdbcTemplate jdbc, long roleId) {
        return Set.copyOf(jdbc.queryForList("""
            SELECT menu.permission
            FROM sys_role_menu role_menu
            JOIN sys_menu menu ON menu.id=role_menu.menu_id
            WHERE role_menu.role_id=?
            """, String.class, roleId));
    }

    /** 检查迁移范围内是否仍存在旧 KEY。 */
    private static boolean hasLegacyPermission(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_menu
            WHERE permission LIKE 'model:%'
               OR permission LIKE 'knowledge:base:%'
               OR permission LIKE 'workflow:%'
               OR permission LIKE 'data-sync:%'
               OR permission LIKE 'server:%'
               OR permission LIKE 'automation:device-agent:%'
               OR permission LIKE 'system:task:%'
               OR permission LIKE 'system:session:%'
               OR permission LIKE 'system:audit:%'
               OR permission LIKE 'mail:%'
            """, Integer.class);
        return count != null && count > 0;
    }

    /** 查询全部目录 KEY 与稳定 ID。 */
    private static Map<String, Long> catalogIds(JdbcTemplate jdbc) {
        return jdbc.query("SELECT permission,id FROM sys_menu WHERE type='CATALOG'", resultSet -> {
            Map<String, Long> values = new java.util.TreeMap<>();
            while (resultSet.next()) values.put(resultSet.getString("permission"), resultSet.getLong("id"));
            return values;
        });
    }
}
