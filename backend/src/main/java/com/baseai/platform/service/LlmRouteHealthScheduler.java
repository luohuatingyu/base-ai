package com.baseai.platform.service;

import org.slf4j.Logger;import org.slf4j.LoggerFactory;import org.springframework.boot.context.event.ApplicationReadyEvent;import org.springframework.context.event.EventListener;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;

/** 启动与定时检查模型，并刷新仅供调用使用的内存路由快照。 */
@Component public class LlmRouteHealthScheduler {
    private static final Logger log=LoggerFactory.getLogger(LlmRouteHealthScheduler.class); private final LlmManagementService service;
    public LlmRouteHealthScheduler(LlmManagementService service){this.service=service;}
    @EventListener(ApplicationReadyEvent.class) public void onReady(){refresh();}
    @Scheduled(fixedDelayString="${app.llm.route-health-check-interval-ms:300000}") public void refresh(){try{service.syncRoutes(java.util.List.of());}catch(Exception exception){log.warn("event=llm_route_sync_failed",exception);}}
}
