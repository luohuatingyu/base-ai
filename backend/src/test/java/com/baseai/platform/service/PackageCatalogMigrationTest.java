package com.baseai.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证套餐调整迁移到独立一级目录时的菜单层级和角色授权兼容性。 */
class PackageCatalogMigrationTest {
    /** 迁移应创建目录、移动页面、补齐目录授权，并可重复执行。 */
    @Test
    void movesPackageAdjustmentAndPreservesExistingPagePermission() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:package-catalog-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        insertMenu(jdbc, 1, null, "AI 能力", "CATALOG", "/ai", "ai:catalog", 30);
        insertMenu(jdbc, 2, 1L, "套餐调整", "MENU", "/package-adjustment",
            "ai:package-adjustment:list", 12);
        jdbc.update("INSERT INTO sys_role_menu VALUES (7,2),(8,1)");

        executeMigration(dataSource, "V49__move_package_adjustment_to_package_catalog.sql");

        Long catalogId = jdbc.queryForObject(
            "SELECT id FROM sys_menu WHERE permission='package:catalog'", Long.class);
        assertEquals("套餐管理|/packages|Goods|15", jdbc.queryForObject("""
            SELECT CONCAT(name,'|',path,'|',icon,'|',sort_order)
            FROM sys_menu WHERE permission='package:catalog'
            """, String.class));
        assertEquals(catalogId, jdbc.queryForObject(
            "SELECT parent_id FROM sys_menu WHERE permission='ai:package-adjustment:list'", Long.class));
        assertEquals(10, jdbc.queryForObject(
            "SELECT sort_order FROM sys_menu WHERE permission='ai:package-adjustment:list'", Integer.class));
        assertEquals(Set.of("package:catalog", "ai:package-adjustment:list"), permissionsForRole(jdbc, 7));
        assertEquals(Set.of("ai:catalog"), permissionsForRole(jdbc, 8));

        executeMigration(dataSource, "V49__move_package_adjustment_to_package_catalog.sql");

        assertEquals(catalogId, jdbc.queryForObject(
            "SELECT id FROM sys_menu WHERE permission='package:catalog'", Long.class));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_menu WHERE permission='package:catalog'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role_menu WHERE role_id=7", Integer.class));

        executeMigration(dataSource, "V50__place_package_catalog_after_automation.sql");

        assertEquals(50, jdbc.queryForObject(
            "SELECT sort_order FROM sys_menu WHERE permission='package:catalog'", Integer.class));

        executeMigration(dataSource, "V50__place_package_catalog_after_automation.sql");

        assertEquals(50, jdbc.queryForObject(
            "SELECT sort_order FROM sys_menu WHERE permission='package:catalog'", Integer.class));
    }

    /** 创建迁移所需的最小菜单和角色授权表。 */
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

    /** 插入固定 ID 菜单以验证页面原位迁移和目录创建。 */
    private static void insertMenu(JdbcTemplate jdbc, long id, Long parentId, String name, String type,
                                   String path, String permission, int sortOrder) {
        jdbc.update("""
            INSERT INTO sys_menu(id,parent_id,name,type,path,component,icon,permission,sort_order,
                                 visible,enabled,created_at,updated_at)
            VALUES (?,?,?,?,?,NULL,NULL,?,?,TRUE,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, id, parentId, name, type, path, permission, sortOrder);
    }

    /** 执行指定版本迁移脚本，覆盖升级顺序和重复执行场景。 */
    private static void executeMigration(DriverManagerDataSource dataSource, String migration) {
        new ResourceDatabasePopulator(new ClassPathResource(
            "db/migration/mysql/" + migration)).execute(dataSource);
    }

    /** 查询角色当前拥有的菜单权限。 */
    private static Set<String> permissionsForRole(JdbcTemplate jdbc, long roleId) {
        return Set.copyOf(jdbc.queryForList("""
            SELECT menu.permission FROM sys_role_menu role_menu
            JOIN sys_menu menu ON menu.id=role_menu.menu_id WHERE role_menu.role_id=?
            """, String.class, roleId));
    }
}
