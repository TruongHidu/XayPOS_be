package com.possaas.modules.subscriptionpurchase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** One worker round: claim, settle each event, and activate a newly PAID purchase immediately. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventProcessor {
    private final PaymentEventClaimService claims;
    private final SubscriptionPaymentSettlementService settlement;
    private final PurchaseActivationService activation;

    public int processBatch() {
        int processed = 0;
        for (var claim : claims.claim()) {
            SubscriptionPaymentSettlementService.Result result;
            try {
                result = settlement.settle(claim.eventId(), claim.token());
            } catch (RuntimeException failure) {
                log.warn("Settlement of payment event {} failed: {}", claim.eventId(), failure.getClass().getSimpleName());
                claims.retryLater(claim, PurchaseActivationService.errorCode(failure));
                continue;
            }
            processed++;
            if (result.outcome() == SubscriptionPaymentSettlementService.Outcome.PAID) {
                try { activation.activateNow(result.purchaseId()); }
                catch (RuntimeException failure) {
                    // PAID is committed; the activation job retries from the purchase row.
                    log.warn("Immediate activation of purchase {} failed: {}", result.purchaseId(), failure.getClass().getSimpleName());
                }
            }
        }
        return processed;
    }
}
