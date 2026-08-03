package com.baseai.platform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 应用启动时保证系统内置 DEFAULT 邮件路由存在且保持启用。 */
@Component
public class MailRouteInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final MailManagementService service;

    /** 注入 MySQL 迁移客户端和邮件配置管理服务。 */
    public MailRouteInitializer(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                MailManagementService service) {
        this.jdbcTemplate = jdbcTemplate;
        this.service = service;
    }

    /** 放宽待配置账户字段后，幂等初始化不可删除、不可禁用的默认邮件路由。 */
    @Override
    public void run(ApplicationArguments arguments) {
        jdbcTemplate.execute("ALTER TABLE sys_mail_route MODIFY COLUMN account_id BIGINT NULL");
        service.ensureDefaultRoute();
    }
}
