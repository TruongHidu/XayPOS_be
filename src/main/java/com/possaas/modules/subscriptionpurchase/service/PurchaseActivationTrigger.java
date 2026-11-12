package com.possaas.modules.subscriptionpurchase.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Best-effort immediate activation after an operator committed a PAID purchase; the scheduled job is the fallback. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseActivationTrigger {
    private final PurchaseActivationService activation;

    public void activateAfterCommit(UUID purchaseId) {
        try {
            activation.activateNow(purchaseId);
        } catch (RuntimeException failure) {
            log.warn("Activation of purchase {} after operator action failed: {}", purchaseId, failure.getClass().getSimpleName());
        }
    }
}
