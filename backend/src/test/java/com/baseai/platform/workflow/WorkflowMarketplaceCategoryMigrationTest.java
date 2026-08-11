package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowMarketplaceCategoryMigrationTest {
    /** V17 必须重分市场误分类模板，同时保留管理员分类、系统模板和原生市场节点。 */
    @Test
    void recategorizesOnlyMisplacedMarketplacePluginTemplates() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:marketplace-category;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_node_template(
                id BIGINT PRIMARY KEY,
                template_source VARCHAR(16),
                functional_category VARCHAR(32),
                node_type VARCHAR(64),
                external_key VARCHAR(255),
                name VARCHAR(120),
                description VARCHAR(500),
                external_publisher VARCHAR(120)
            )
            """);
        jdbcTemplate.update("INSERT INTO workflow_node_template VALUES (1,'N8N','NETWORK_API','PLUGIN_ACTION',"
            + "'n8n-nodes-slack/send','Slack message','Send a channel notification','vendor')");
        jdbcTemplate.update("INSERT INTO workflow_node_template VALUES (2,'DIFY','NETWORK_API','PLUGIN_DATASOURCE',"
            + "'vendor/source','Source','Generic source','vendor')");
        jdbcTemplate.update("INSERT INTO workflow_node_template VALUES (3,'N8N','NETWORK_API','PLUGIN_ACTION',"
            + "'vendor/helper','Helper','Unclassified productivity helper','vendor')");
        jdbcTemplate.update("INSERT INTO workflow_node_template VALUES (4,'DIFY','TEXT_DOCUMENT','PLUGIN_ACTION',"
            + "'vendor/slack-doc','Slack document','Administrator selected category','vendor')");
        jdbcTemplate.update("INSERT INTO workflow_node_template VALUES (5,'SYSTEM','NETWORK_API','PLUGIN_ACTION',"
            + "'system/slack','Slack system node','System template','system')");
        jdbcTemplate.update("INSERT INTO workflow_node_template VALUES (6,'DIFY','NETWORK_API','TAVILY_TOOL',"
            + "'langgenius/tavily/search','Tavily Search','Search the web','langgenius')");

        new ResourceDatabasePopulator(new ClassPathResource(
            "db/migration/mysql/V17__recategorize_marketplace_plugin_templates.sql")).execute(dataSource);

        Map<Long, String> categories = jdbcTemplate.query("SELECT id,functional_category FROM workflow_node_template",
            resultSet -> {
                Map<Long, String> values = new java.util.LinkedHashMap<>();
                while (resultSet.next()) values.put(resultSet.getLong("id"), resultSet.getString("functional_category"));
                return values;
            });
        assertEquals(Map.of(1L, "NOTIFICATION", 2L, "DATA_STORAGE", 3L, "BASIC",
            4L, "TEXT_DOCUMENT", 5L, "NETWORK_API", 6L, "NETWORK_API"), categories);
    }
}
