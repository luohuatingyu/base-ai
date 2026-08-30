package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowPluginAdmissionMigrationTest {
    /** V18 必须记录存量启用值，并把全部存量插件安全转为待审批停用。 */
    @Test
    void disablesExistingPluginsAndPreservesPreviousEnabledState() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:plugin-admission;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE workflow_marketplace_plugin(
                id BIGINT PRIMARY KEY,source VARCHAR(16),package_key VARCHAR(255),enabled BOOLEAN,updated_at TIMESTAMP)
            """);
        jdbcTemplate.update("INSERT INTO workflow_marketplace_plugin VALUES (1,'N8N','enabled-plugin',true,NOW())");
        jdbcTemplate.update("INSERT INTO workflow_marketplace_plugin VALUES (2,'DIFY','disabled-plugin',false,NOW())");

        String mysql = new ClassPathResource("db/migration/mysql/V18__add_plugin_admission_control.sql")
            .getContentAsString(StandardCharsets.UTF_8).replace("BIT(1)", "BOOLEAN").replace("b'0'", "false")
            .replace("UPDATE workflow_marketplace_plugin p\nJOIN workflow_plugin_admission a ON a.plugin_id=p.id\n"
                + "SET p.enabled=false,p.updated_at=CURRENT_TIMESTAMP(6)\nWHERE a.admission_status='PENDING'",
                "UPDATE workflow_marketplace_plugin SET enabled=false,updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE id IN (SELECT plugin_id FROM workflow_plugin_admission WHERE admission_status='PENDING')");
        new ResourceDatabasePopulator(new ByteArrayResource(mysql.getBytes(StandardCharsets.UTF_8))).execute(dataSource);

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workflow_marketplace_plugin WHERE enabled=true", Integer.class));
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workflow_plugin_admission WHERE admission_status='PENDING'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workflow_plugin_admission WHERE enabled_before_admission=true", Integer.class));
    }
}
