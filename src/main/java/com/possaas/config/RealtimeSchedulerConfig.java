package com.possaas.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class RealtimeSchedulerConfig {
    @Bean("realtimeTaskScheduler")
    ThreadPoolTaskScheduler realtimeTaskScheduler(Clock clock) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setClock(clock);
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("inventory-realtime-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
