package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentAttemptRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class PurchaseExpirationService {
    private final SubscriptionPurchaseRepository purchases;
    private final SubscriptionPaymentAttemptRepository attempts;
    private final RestaurantRepository restaurants;
    private final AuditService audits;
    private final Clock clock;
    private final TransactionTemplate perPurchase;
    private final int batchSize;

    public PurchaseExpirationService(SubscriptionPurchaseRepository purchases, SubscriptionPaymentAttemptRepository attempts,
        RestaurantRepository restaurants, AuditService audits, Clock clock, PlatformTransactionManager transactions,
        @Value("${app.jobs.subscription-purchase-expiration.batch-size:100}") int batchSize) {
        this.purchases = purchases; this.attempts = attempts; this.restaurants = restaurants; this.audits = audits;
        this.clock = clock; this.batchSize = batchSize;
        perPurchase = new TransactionTemplate(transactions);
        perPurchase.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Caller owns restaurant then purchase locks; no scheduler or nested transaction here. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireIfDue(SubscriptionPurchase purchase, Instant now) {
        if (!purchase.isDue(now) || purchase.getPaidAt() != null) return false;
        attempts.lockPending(purchase.getRestaurantId(), purchase.getId());
        attempts.endPending(purchase.getRestaurantId(), purchase.getId(), "EXPIRED", now);
        purchase.expire(now);
        purchases.flush();
        audits.record(purchase.getRestaurantId(), null, "SUBSCRIPTION_PURCHASE_EXPIRED", "subscription_purchases",
            purchase.getId(), Map.of("status", "PENDING_PAYMENT"), Map.of("status", "EXPIRED"), null);
        return true;
    }

    /**
     * Candidates are read without locks; each purchase then commits alone in lock order restaurant → purchase → attempts.
     * Suspended restaurants are expired too, so the restaurant status is not checked here.
     */
    public int expireDueBatch() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int expired = 0;
        for (var due : purchases.findDue(now, batchSize)) {
            try {
                Boolean done = perPurchase.execute(tx -> {
                    restaurants.findByIdForUpdate(due.getRestaurantId());
                    return purchases.findForUpdate(due.getId(), due.getRestaurantId()).map(p -> expireIfDue(p, now)).orElse(false);
                });
                if (Boolean.TRUE.equals(done)) expired++;
            } catch (RuntimeException failure) {
                log.warn("Purchase expiration failed for purchase {}: {}", due.getId(), failure.getClass().getSimpleName());
            }
        }
        return expired;
    }
}
