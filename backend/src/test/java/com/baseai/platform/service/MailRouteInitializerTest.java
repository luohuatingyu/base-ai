package com.baseai.platform.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

class MailRouteInitializerTest {
    /** 应用启动入口只负责调用邮件 DEFAULT 初始化逻辑。 */
    @Test
    void initializesDefaultRouteOnStartup() {
        MailManagementService service = mock(MailManagementService.class);

        new MailRouteInitializer(service).run(null);

        verify(service).ensureDefaultRoute();
    }
}
