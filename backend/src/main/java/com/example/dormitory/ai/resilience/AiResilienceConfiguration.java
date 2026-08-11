package com.example.dormitory.ai.resilience;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class AiResilienceConfiguration {

    @Bean(destroyMethod = "close")
    AiResiliencePolicy aiResiliencePolicy() {
        return new AiResiliencePolicy(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ai-resilience-", 0).factory()),
                System::currentTimeMillis);
    }
}
