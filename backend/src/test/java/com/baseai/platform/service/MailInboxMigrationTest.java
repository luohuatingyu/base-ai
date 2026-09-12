package com.baseai.platform.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** 实际执行收件迁移，验证旧记录及关联外键生命周期。 */
class MailInboxMigrationTest {
    /** 旧邮箱默认关闭收件，角色删除不会留下无效授权。 */
    @Test
    void migratesLegacyAccountsAndCascadesBindings() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:mail" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_mail_account(id BIGINT PRIMARY KEY, password_encrypted TEXT)");
        jdbc.execute("CREATE TABLE sys_role(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO sys_mail_account VALUES(1, 'existing-secret')");
        jdbc.update("INSERT INTO sys_role VALUES(7)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/mysql/V39__add_mail_inbox.sql")).execute(source);
        assertFalse(jdbc.queryForObject("SELECT imap_enabled FROM sys_mail_account", Boolean.class));
        assertEquals("existing-secret", jdbc.queryForObject("SELECT password_encrypted FROM sys_mail_account", String.class));
        jdbc.update("INSERT INTO sys_mail_account_role VALUES(1,7)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> jdbc.update("INSERT INTO sys_mail_account_role VALUES(1,99)"));
        jdbc.update("DELETE FROM sys_role WHERE id=7");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_mail_account_role", Integer.class));
    }
}
