package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.PurchasedSubscriptionActivation;
import com.possaas.modules.subscriptionpurchase.config.PaymentWorkerProperties;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseQuote;
import com.possaas.modules.subscriptionpurchase.entity.PaymentEventState;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.ReviewReason;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentEventRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a PAID purchase into an ACTIVE subscription through the subscription module's port.
 * Claim (lease + token) commits first; activation runs in its own transaction; technical failures are rescheduled.
 */
@Slf4j
@Service
public class PurchaseActivationService {
    public enum Outcome { ACTIVATED, REVIEW, RETRY, SKIPPED }
    private record Claim(UUID purchaseId, UUID restaurantId, UUID token) {}

    private final SubscriptionPurchaseRepository purchases;
    private final SubscriptionPaymentEventRepository events;
    private final RestaurantRepository restaurants;
    private final PurchasedSubscriptionActivation activation;
    private final PaymentWorkerProperties worker;
    private final AuditService audits;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PurchaseActivationService(SubscriptionPurchaseRepository purchases, SubscriptionPaymentEventRepository events,
        RestaurantRepository restaurants, PurchasedSubscriptionActivation activation, PaymentWorkerProperties worker,
        AuditService audits, Clock clock, PlatformTransactionManager transactions) {
        this.purchases=purchases; this.events=events; this.restaurants=restaurants; this.activation=activation;
        this.worker=worker; this.audits=audits; this.clock=clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Fast path right after settlement; the scheduled batch is the durable fallback. */
    public Outcome activateNow(UUID purchaseId) {
        Claim claim = transaction.execute(tx -> {
            Instant now = now();
            UUID tenant = purchases.findRestaurantId(purchaseId).orElse(null);
            if (tenant == null) return null;
            var purchase = purchases.findForUpdate(purchaseId, tenant).orElse(null);
            if (purchase == null || !claimable(purchase, now)) return null;
            return claim(purchase, now);
        });
        return claim == null ? Outcome.SKIPPED : run(claim);
    }

    public int activateDueBatch() {
        List<Claim> claims = transaction.execute(tx -> {
            Instant now = now();
            List<Claim> claimed = new ArrayList<>();
            for (UUID id : purchases.lockActivationDue(now, worker.getBatchSize()))
                purchases.findById(id).filter(p -> claimable(p, now)).ifPresent(p -> claimed.add(claim(p, now)));
            return claimed;
        });
        int activated = 0;
        for (Claim claim : claims) if (run(claim) == Outcome.ACTIVATED) activated++;
        return activated;
    }

    private Outcome run(Claim claim) {
        try {
            return transaction.execute(tx -> activateLocked(claim));
        } catch (RuntimeException failure) {
            log.warn("Activation of purchase {} failed: {}", claim.purchaseId(), failure.getClass().getSimpleName());
            reschedule(claim, errorCode(failure));
            return Outcome.RETRY;
        }
    }

    private Outcome activateLocked(Claim claim) {
        UUID tenant = claim.restaurantId();
        var restaurant = restaurants.findByIdForUpdate(tenant).orElse(null);
        var purchase = purchases.findForUpdate(claim.purchaseId(), tenant).orElse(null);
        if (purchase == null || !purchase.activationHeldBy(claim.token()) || purchase.getSubscriptionId() != null) return Outcome.SKIPPED;
        Instant now = now();
        if (restaurant == null || restaurant.getStatus() != RestaurantStatus.ACTIVE) return review(purchase, ReviewReason.RESTAURANT_UNAVAILABLE, now);
        PurchaseQuote quote = PurchaseQuote.fromMap(purchase.getQuote());
        PurchasedSubscriptionActivation.Result result;
        try {
            result = activation.activate(new PurchasedSubscriptionActivation.Command(tenant, purchase.getId(), quote.packageId(),
                quote.packageCode(), BigDecimal.valueOf(quote.priceAmount()), quote.currencyCode(), quote.billingCycleMonths(),
                quote.billingZoneId(), quote.features(), quote.maxStaff(), now));
        } catch (PurchasedSubscriptionActivation.ActivationBlockedException blocked) {
            return review(purchase, ReviewReason.SUBSCRIPTION_CONFLICT, now);
        }
        purchase.markActivated(result.subscriptionId(), now);
        purchases.flush();
        audits.record(tenant, null, "SUBSCRIPTION_PURCHASE_ACTIVATED", "subscription_purchases", purchase.getId(),
            Map.of("status", "PAID"), Map.of("status", "ACTIVATED", "subscriptionId", result.subscriptionId().toString(),
                "startAt", result.startAt().toString(), "endAt", result.endAt().toString()), null);
        return Outcome.ACTIVATED;
    }

    /** Business blocker: money stays recorded (paidAt, attempt SUCCESS); purchase and its paid event wait for an operator. */
    private Outcome review(SubscriptionPurchase purchase, ReviewReason reason, Instant now) {
        purchase.markReview(reason.name(), now);
        purchases.flush();
        if (purchase.getPaidEventId() != null) events.findForUpdate(purchase.getPaidEventId())
            .filter(e -> e.getState() == PaymentEventState.PROCESSED).ifPresent(e -> { e.review(reason, now); events.flush(); });
        audits.record(purchase.getRestaurantId(), null, "SUBSCRIPTION_PURCHASE_REVIEW_REQUIRED", "subscription_purchases",
            purchase.getId(), Map.of("status", "PAID"), Map.of("status", "REVIEW_REQUIRED", "reviewReason", reason.name()), null);
        return Outcome.REVIEW;
    }

    private void reschedule(Claim claim, String errorCode) {
        transaction.executeWithoutResult(tx -> {
            var locked = purchases.findForUpdate(claim.purchaseId(), claim.restaurantId()).orElse(null);
            if (locked == null || !locked.activationHeldBy(claim.token())) return;
            Instant now = now();
            locked.scheduleActivationRetry(now.plus(worker.backoffAfter(locked.getActivationRetryCount())), errorCode, now);
            purchases.flush();
        });
    }

    private boolean claimable(SubscriptionPurchase p, Instant now) {
        return p.getStatus() == PurchaseStatus.PAID && p.getActivationNextAt() != null
            && !p.getActivationNextAt().isAfter(now) && (p.getActivationLeaseUntil() == null || p.getActivationLeaseUntil().isBefore(now));
    }

    private Claim claim(SubscriptionPurchase purchase, Instant now) {
        UUID token = UUID.randomUUID();
        purchase.claimActivation(token, now.plus(worker.getLease()), now);
        purchases.flush();
        return new Claim(purchase.getId(), purchase.getRestaurantId(), token);
    }

    static String errorCode(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        return name.length() > 60 ? name.substring(0, 60) : name;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
