package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PurchaseExpirationService {
    private final SubscriptionPurchaseRepository purchases;
    private final AuditService audits;

    /** Caller owns restaurant then purchase locks; no scheduler or nested transaction here. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireIfDue(SubscriptionPurchase purchase, Instant now) {
        if (!purchase.isDue(now) || purchase.getPaidAt() != null) return false;
        purchases.lockPendingAttempts(purchase.getRestaurantId(), purchase.getId());
        purchases.endPendingAttempts(purchase.getRestaurantId(), purchase.getId(), "EXPIRED", now);
        purchase.expire(now);
        purchases.flush();
        audits.record(purchase.getRestaurantId(), null, "SUBSCRIPTION_PURCHASE_EXPIRED", "subscription_purchases",
            purchase.getId(), Map.of("status", "PENDING_PAYMENT"), Map.of("status", "EXPIRED"), null);
        return true;
    }
}
