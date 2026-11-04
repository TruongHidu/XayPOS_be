package com.possaas.modules.subscriptionpurchase.jobs;

import com.possaas.modules.subscriptionpurchase.service.PaymentEventProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.jobs.subscription-payment-events.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class PaymentEventWorkerJob {
    private final PaymentEventProcessor processor;

    @Scheduled(
        fixedDelayString = "${app.jobs.subscription-payment-events.fixed-delay:PT5S}",
        initialDelayString = "${app.jobs.subscription-payment-events.initial-delay:PT30S}"
    )
    public void run() {
        processor.processBatch();
    }
}
