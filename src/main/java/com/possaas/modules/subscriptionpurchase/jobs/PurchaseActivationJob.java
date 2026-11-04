package com.possaas.modules.subscriptionpurchase.jobs;

import com.possaas.modules.subscriptionpurchase.service.PurchaseActivationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable fallback when the immediate activation after settlement failed or the process stopped. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.jobs.subscription-purchase-activation.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class PurchaseActivationJob {
    private final PurchaseActivationService activation;

    @Scheduled(
        fixedDelayString = "${app.jobs.subscription-purchase-activation.fixed-delay:PT10S}",
        initialDelayString = "${app.jobs.subscription-purchase-activation.initial-delay:PT30S}"
    )
    public void run() {
        activation.activateDueBatch();
    }
}
