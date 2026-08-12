package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowPluginLocalizationMigrationTest {
    /** V19 必须为存量组件和模板补齐空展示元数据，且不改变审批或启用状态。 */
    @Test
    void backfillsLocalizationWithoutChangingExistingState() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:plugin-localization;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_marketplace_component(id BIGINT PRIMARY KEY,description VARCHAR(1000))
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_node_template(id BIGINT PRIMARY KEY,description VARCHAR(500),enabled BOOLEAN)
            """);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_plugin_admission(plugin_id BIGINT PRIMARY KEY,admission_status VARCHAR(24))
            """);
        jdbcTemplate.update("INSERT INTO workflow_marketplace_component VALUES (1,'component')");
        jdbcTemplate.update("INSERT INTO workflow_node_template VALUES (1,'template',true)");
        jdbcTemplate.update("INSERT INTO workflow_plugin_admission VALUES (1,'APPROVED')");

        new ResourceDatabasePopulator(new ClassPathResource(
            "db/migration/mysql/V19__add_workflow_plugin_localization.sql")).execute(dataSource);

        assertEquals("{}", jdbcTemplate.queryForObject(
            "SELECT localization_json FROM workflow_marketplace_component WHERE id=1", String.class));
        assertEquals("{}", jdbcTemplate.queryForObject(
            "SELECT localization_json FROM workflow_node_template WHERE id=1", String.class));
        assertEquals(true, jdbcTemplate.queryForObject(
            "SELECT enabled FROM workflow_node_template WHERE id=1", Boolean.class));
        assertEquals("APPROVED", jdbcTemplate.queryForObject(
            "SELECT admission_status FROM workflow_plugin_admission WHERE plugin_id=1", String.class));
    }

    /** V20 必须为历史运行节点补齐空展示身份，同时原始节点名保持不变。 */
    @Test
    void backfillsNodeRunLocalizationWithoutChangingOriginalName() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:node-run-localization;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_node_run(id BIGINT PRIMARY KEY,node_name VARCHAR(120))
            """);
        jdbcTemplate.update("INSERT INTO workflow_node_run VALUES (1,'自定义节点名')");

        new ResourceDatabasePopulator(new ClassPathResource(
            "db/migration/mysql/V20__localize_workflow_node_runs.sql")).execute(dataSource);

        assertEquals("自定义节点名", jdbcTemplate.queryForObject(
            "SELECT node_name FROM workflow_node_run WHERE id=1", String.class));
        assertEquals("", jdbcTemplate.queryForObject(
            "SELECT default_node_name FROM workflow_node_run WHERE id=1", String.class));
        assertEquals("{}", jdbcTemplate.queryForObject(
            "SELECT localization_json FROM workflow_node_run WHERE id=1", String.class));
    }
}
