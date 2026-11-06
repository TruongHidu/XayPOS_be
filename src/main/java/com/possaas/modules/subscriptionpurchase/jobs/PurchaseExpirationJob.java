package com.possaas.modules.subscriptionpurchase.jobs;

import com.possaas.modules.subscriptionpurchase.service.PurchaseExpirationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.jobs.subscription-purchase-expiration.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class PurchaseExpirationJob {
    private final PurchaseExpirationService expirationService;

    @Scheduled(
        fixedDelayString = "${app.jobs.subscription-purchase-expiration.fixed-delay:PT1M}",
        initialDelayString = "${app.jobs.subscription-purchase-expiration.initial-delay:PT1M}"
    )
    public void run() {
        expirationService.expireDueBatch();
    }
}
