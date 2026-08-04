package com.baseai.platform.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 应用启动时保证系统内置 DEFAULT 邮件路由存在且保持启用。 */
@Component
public class MailRouteInitializer implements ApplicationRunner {
    private final MailManagementService service;

    /** 注入邮件配置管理服务。 */
    public MailRouteInitializer(MailManagementService service) {
        this.service = service;
    }

    /** 初始化不可删除、不可禁用的默认邮件路由。 */
    @Override
    public void run(ApplicationArguments arguments) {
        service.ensureDefaultRoute();
    }
}
