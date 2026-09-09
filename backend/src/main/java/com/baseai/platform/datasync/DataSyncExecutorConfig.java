package com.baseai.platform.datasync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 创建有界数据同步线程池，避免批量复制占满 Web 请求线程。 */
@Configuration
public class DataSyncExecutorConfig {
    /** 创建最多两个并发同步任务和有限等待队列。 */
    @Bean("dataSyncTaskExecutor")
    public ThreadPoolTaskExecutor dataSyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("data-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
