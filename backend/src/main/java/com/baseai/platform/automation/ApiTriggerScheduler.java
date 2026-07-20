package com.baseai.platform.automation;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class ApiTriggerScheduler {
    private static final Logger log = LoggerFactory.getLogger(ApiTriggerScheduler.class);
    private final ApiTriggerService service;
    private final ApiTriggerExecutionService executionService;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public ApiTriggerScheduler(ApiTriggerService service, ApiTriggerExecutionService executionService,
                               com.baseai.platform.config.PlatformProperties properties) {
        this.service = service;
        this.executionService = executionService;
        scheduler.setPoolSize(properties.getApiTrigger().getSchedulerPoolSize());
        scheduler.setThreadNamePrefix("api-trigger-");
        scheduler.initialize();
    }

    /** 应用就绪后从 PostgreSQL 加载全部启用 Cron 配置。 */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try { service.findEnabled().forEach(this::schedule); }
        catch (Exception exception) { log.error("event=api_trigger_schedule_init_failed", exception); }
    }

    /** 根据最新配置取消并重新注册单个任务。 */
    public void reschedule(Long id) {
        cancel(id);
        try {
            ApiTriggerModels.View config = service.get(id);
            if (config.enabled() && !config.voided() && config.cronExpression() != null) schedule(config);
        } catch (Exception exception) { log.warn("event=api_trigger_reschedule_failed id={}", id, exception); }
    }

    public void cancel(Long id) {
        ScheduledFuture<?> future = tasks.remove(id);
        if (future != null) future.cancel(false);
    }

    /** 注册单个动态 Cron。 */
    private void schedule(ApiTriggerModels.View config) {
        ScheduledFuture<?> future = scheduler.schedule(
            () -> executionService.executeScheduled(config.id(), config.ownerUserId()),
            new CronTrigger(config.cronExpression())
        );
        if (future != null) tasks.put(config.id(), future);
    }

    @PreDestroy
    public void shutdown() { scheduler.shutdown(); }
}
