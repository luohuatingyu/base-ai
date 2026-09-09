package com.baseai.platform.deployment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 创建有界部署线程池，避免 SSH 或 Compose 执行阻塞 HTTP 请求。 */
@Configuration
public class DeploymentExecutorConfig {
    /** 创建单并发部署线程和有限等待队列。 */
    @Bean("deploymentTaskExecutor")
    public ThreadPoolTaskExecutor deploymentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("deployment-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
