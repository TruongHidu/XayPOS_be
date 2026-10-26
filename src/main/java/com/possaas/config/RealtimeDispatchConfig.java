package com.possaas.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RealtimeDispatchConfig {
    @Bean("realtimeDispatchExecutor")
    ThreadPoolTaskExecutor realtimeDispatchExecutor(
            @Value("${app.realtime.dispatch-threads:2}") int threads,
            @Value("${app.realtime.dispatch-queue-capacity:256}") int capacity) {
        if (threads < 1 || threads > 16 || capacity < 1 || capacity > 10000)
            throw new IllegalArgumentException("Invalid realtime dispatch limits");
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads); executor.setMaxPoolSize(threads); executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix("realtime-dispatch-"); executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
