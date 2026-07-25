package com.baseai.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LlmRouteHealthSchedulerTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SchedulerConfiguration.class)
        .withBean(LlmManagementService.class, () -> mock(LlmManagementService.class));

    /** 未显式开启自动同步时，不应创建调度器。 */
    @Test
    void schedulerIsDisabledByDefault() {
        contextRunner.run(context -> context.assertThat().doesNotHaveBean(LlmRouteHealthScheduler.class));
    }

    /** 显式开启自动同步时，应创建调度器。 */
    @Test
    void schedulerIsCreatedWhenEnabled() {
        contextRunner
            .withPropertyValues("app.llm.route-health-check-enabled=true")
            .run(context -> context.assertThat().hasSingleBean(LlmRouteHealthScheduler.class));
    }

    /** 应用启动完成后应立即同步一次模型路由。 */
    @Test
    void applicationReadyTriggersImmediateSync() {
        LlmManagementService service = mock(LlmManagementService.class);
        LlmRouteHealthScheduler scheduler = new LlmRouteHealthScheduler(service);

        scheduler.onReady();

        verify(service).syncRoutes(java.util.List.of());
    }

    /** 单次同步失败时不应中断后续调度。 */
    @Test
    void refreshIsolatesSyncFailure() {
        LlmManagementService service = mock(LlmManagementService.class);
        LlmRouteHealthScheduler scheduler = new LlmRouteHealthScheduler(service);
        doThrow(new IllegalStateException("sync failed")).when(service).syncRoutes(java.util.List.of());

        assertDoesNotThrow(scheduler::refresh);
    }

    /** 默认调度间隔应为一小时。 */
    @Test
    void defaultIntervalIsOneHour() throws NoSuchMethodException {
        Scheduled scheduled = LlmRouteHealthScheduler.class.getMethod("refresh").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = LlmRouteHealthScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertEquals("${app.llm.route-health-check-interval-ms:3600000}", scheduled.fixedDelayString());
        assertEquals("true", condition.havingValue());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LlmRouteHealthScheduler.class)
    static class SchedulerConfiguration {
    }
}
