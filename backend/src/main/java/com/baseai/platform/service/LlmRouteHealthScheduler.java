package com.baseai.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 启动与定时检查模型，并刷新仅供调用使用的内存路由快照。 */
@Component
@ConditionalOnProperty(prefix = "app.llm", name = "route-health-check-enabled", havingValue = "true")
public class LlmRouteHealthScheduler {
    private static final Logger log = LoggerFactory.getLogger(LlmRouteHealthScheduler.class);
    private final LlmManagementService service;

    /** 创建模型路由健康检查调度器。 */
    public LlmRouteHealthScheduler(LlmManagementService service) {
        this.service = service;
    }

    /** 应用启动完成后立即执行一次模型同步。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    /** 按配置间隔同步模型状态，并隔离单次同步异常。 */
    @Scheduled(fixedDelayString = "${app.llm.route-health-check-interval-ms:3600000}")
    public void refresh() {
        try {
            service.syncRoutes(java.util.List.of());
        } catch (Exception exception) {
            log.warn("event=llm_route_sync_failed", exception);
        }
    }
}
