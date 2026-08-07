package com.baseai.platform.workflow;

import com.baseai.platform.config.PlatformProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 创建有界工作流线程池，避免长循环占用 Web 请求线程。 */
@Configuration
public class WorkflowExecutorConfig {
    /** 按配置创建固定核心线程和有界队列。 */
    @Bean("workflowTaskExecutor")
    public ThreadPoolTaskExecutor workflowTaskExecutor(PlatformProperties properties) {
        int size = Math.max(1, Math.min(properties.getWorkflow().getExecutorPoolSize(), 32));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(size * 100);
        executor.setThreadNamePrefix("workflow-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
