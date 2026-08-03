package com.baseai.platform.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LlmDefaultRouteInitializerTest {
    /** 应用启动入口必须调用模型 DEFAULT 路由的幂等纠正逻辑。 */
    @Test
    void initializesDefaultRouteOnStartup() {
        LlmManagementService service = mock(LlmManagementService.class);

        new LlmDefaultRouteInitializer(service).run(null);

        verify(service).ensureDefaultRoute();
    }
}
