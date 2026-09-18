package com.possaas.modules.subscription.jobs;

import com.possaas.modules.subscription.service.SubscriptionExpirationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.jobs.subscription-expiration.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class SubscriptionExpirationJob {
    private final SubscriptionExpirationService expirationService;

    @Scheduled(
        fixedDelayString = "${app.jobs.subscription-expiration.fixed-delay:PT1M}",
        initialDelayString = "${app.jobs.subscription-expiration.initial-delay:PT1M}"
    )
    public void run() {
        expirationService.expireDueBatch();
    }
}
