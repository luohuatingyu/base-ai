package com.baseai.platform.workflow;

import com.baseai.platform.config.PlatformProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/** 为第三方插件下载和 ABI 探测创建独立有界线程池。 */
@Configuration
public class WorkflowPluginProbeExecutorConfig {
    /** 使用独立资源边界，避免依赖安装占用工作流执行线程。 */
    @Bean("workflowPluginProbeExecutor")
    public ThreadPoolTaskExecutor workflowPluginProbeExecutor(PlatformProperties properties) {
        int size = Math.max(1, Math.min(properties.getWorkflow().getMarketplaceProbeConcurrency(), 8));
        int capacity = Math.max(1, Math.min(properties.getWorkflow().getMarketplaceProbeQueueCapacity(), 1000));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix("plugin-probe-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
