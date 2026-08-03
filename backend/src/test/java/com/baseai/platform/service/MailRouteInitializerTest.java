package com.baseai.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MailRouteInitializerTest {
    /** 应用启动入口必须先放宽账户字段，再调用邮件 DEFAULT 初始化逻辑。 */
    @Test
    void initializesDefaultRouteOnStartup() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        MailManagementService service = mock(MailManagementService.class);

        new MailRouteInitializer(jdbcTemplate, service).run(null);

        verify(jdbcTemplate).execute("ALTER TABLE sys_mail_route MODIFY COLUMN account_id BIGINT NULL");
        verify(service).ensureDefaultRoute();
    }
}
