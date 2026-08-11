package com.example.dormitory.ai.infrastructure.runtime;

import com.example.dormitory.ai.config.AiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class AiRuntimeExecutorConfiguration {

    @Bean("aiRunTaskExecutor")
    public ThreadPoolTaskExecutor aiRunTaskExecutor(AiProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getRuntime().getExecutorCoreSize());
        executor.setMaxPoolSize(properties.getRuntime().getExecutorMaxSize());
        executor.setQueueCapacity(properties.getRuntime().getExecutorQueueCapacity());
        executor.setThreadNamePrefix("ai-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    @Bean("aiHeartbeatScheduler")
    public ThreadPoolTaskScheduler aiHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ai-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
