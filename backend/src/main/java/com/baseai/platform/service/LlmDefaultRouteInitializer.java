package com.baseai.platform.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 应用启动时保证模型 DEFAULT 路由存在并恢复固定编码和名称。 */
@Component
public class LlmDefaultRouteInitializer implements ApplicationRunner {
    private final LlmManagementService service;

    /** 注入模型配置管理服务。 */
    public LlmDefaultRouteInitializer(LlmManagementService service) {
        this.service = service;
    }

    /** 幂等初始化模型 DEFAULT 路由并纠正历史名称。 */
    @Override
    public void run(ApplicationArguments arguments) {
        service.ensureDefaultRoute();
    }
}
