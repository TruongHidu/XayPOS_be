package com.possaas.modules.subscriptionpurchase.config;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Shared lease/backoff policy for the payment event worker and the purchase activation worker. */
@Component @ConfigurationProperties("app.jobs.subscription-payment-events") @Getter @Setter
public class PaymentWorkerProperties {
    private int batchSize = 20;
    private Duration lease = Duration.ofSeconds(60);
    private Duration retryExhaustedAfter = Duration.ofHours(24);
    private List<Duration> backoff = List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2),
        Duration.ofMinutes(10), Duration.ofMinutes(30));

    public Duration backoffAfter(int failures) {
        return backoff.get(Math.min(Math.max(failures, 0), backoff.size() - 1));
    }
}
