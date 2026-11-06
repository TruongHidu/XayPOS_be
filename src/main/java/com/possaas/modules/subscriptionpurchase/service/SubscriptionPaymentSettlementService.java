package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.subscriptionpurchase.application.port.PlatformAccountRegistry;
import com.possaas.modules.subscriptionpurchase.config.PurchaseProperties;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.ReviewReason;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPaymentAttempt;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPaymentEvent;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentAttemptRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentEventRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one claimed event in a single transaction, locking restaurant → purchase → attempts → subscription → event.
 * The decision itself comes from {@link SettlementPolicy}; this class only locks, applies and audits.
 */
@Service
public class SubscriptionPaymentSettlementService {
    public enum Outcome { PAID, REVIEW, PROCESSED, SKIPPED }
    public record Result(Outcome outcome, UUID purchaseId) {}

    private final SubscriptionPaymentEventRepository events;
    private final SubscriptionPaymentAttemptRepository attempts;
    private final SubscriptionPurchaseRepository purchases;
    private final RestaurantSubscriptionRepository subscriptions;
    private final RestaurantRepository restaurants;
    private final PaymentReferenceExtractor references;
    private final SettlementPolicy policy;
    private final PlatformAccountRegistry accounts;
    private final PurchaseExpirationService expiration;
    private final PurchaseProperties settings;
    private final AuditService audits;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SubscriptionPaymentSettlementService(SubscriptionPaymentEventRepository events, SubscriptionPaymentAttemptRepository attempts,
        SubscriptionPurchaseRepository purchases, RestaurantSubscriptionRepository subscriptions, RestaurantRepository restaurants,
        PaymentReferenceExtractor references, SettlementPolicy policy, PlatformAccountRegistry accounts,
        PurchaseExpirationService expiration, PurchaseProperties settings, AuditService audits, Clock clock,
        PlatformTransactionManager transactions) {
        this.events=events; this.attempts=attempts; this.purchases=purchases; this.subscriptions=subscriptions;
        this.restaurants=restaurants; this.references=references; this.policy=policy; this.accounts=accounts;
        this.expiration=expiration; this.settings=settings; this.audits=audits; this.clock=clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Result settle(UUID eventId, UUID token) {
        var event = events.findById(eventId).orElse(null);
        if (event == null) return new Result(Outcome.SKIPPED, null);
        var extraction = references.extract(text(event, "code"), text(event, "content"));
        if (extraction.outcome() != PaymentReferenceExtractor.Outcome.MATCHED)
            return transaction.execute(tx -> unmatched(eventId, token, extraction.outcome() == PaymentReferenceExtractor.Outcome.AMBIGUOUS
                ? ReviewReason.REFERENCE_AMBIGUOUS : ReviewReason.REFERENCE_UNMATCHED));
        var attempt = attempts.findByReferenceCode(extraction.reference()).orElse(null);
        if (attempt == null) return transaction.execute(tx -> unmatched(eventId, token, ReviewReason.REFERENCE_UNMATCHED));
        return transaction.execute(tx -> settleLocked(eventId, token, attempt.getRestaurantId(), attempt.getPurchaseId(), attempt.getId()));
    }

    private Result unmatched(UUID eventId, UUID token, ReviewReason reason) {
        var event = events.findForUpdate(eventId).orElse(null);
        if (event == null || !event.heldBy(token)) return new Result(Outcome.SKIPPED, null);
        event.review(reason, now());
        events.flush();
        auditReview(event, reason);
        return new Result(Outcome.REVIEW, null);
    }

    private Result settleLocked(UUID eventId, UUID token, UUID tenant, UUID purchaseId, UUID attemptId) {
        restaurants.findByIdForUpdate(tenant);
        SubscriptionPurchase purchase = purchases.findForUpdate(purchaseId, tenant).orElseThrow();
        SubscriptionPaymentAttempt attempt = attempts.lockAll(tenant, purchaseId).stream()
            .filter(a -> a.getId().equals(attemptId)).findFirst().orElseThrow();
        Instant now = now();
        boolean blocking = false, otherOpen = false;
        if (purchase.getStatus() == PurchaseStatus.EXPIRED) {
            blocking = subscriptions.findActiveForUpdate(tenant).filter(s -> s.getEndAt().isAfter(now)).isPresent()
                || subscriptions.findByRestaurantIdAndStatus(tenant, SubscriptionStatus.PENDING).isPresent();
            otherOpen = purchases.findOpen(tenant).filter(p -> !p.getId().equals(purchaseId)).isPresent();
        }
        SubscriptionPaymentEvent event = events.findForUpdate(eventId).orElse(null);
        if (event == null || !event.heldBy(token)) return new Result(Outcome.SKIPPED, null);

        var decision = policy.decide(new SettlementPolicy.Facts(event.getId(), event.getDirection(), event.getAmount(),
            event.getCurrencyCode(), event.getReceiverAccount(), text(event, "subAccount"), event.getOccurredAt(),
            accounts.accountNumber(attempt.getProviderAccountKey()), attempt.getAmount(), attempt.canSettle(),
            attempt.getCreatedAt(), attempt.getExpiresAt(), purchase.getStatus(), purchase.getPaidEventId(),
            purchase.getReviewReason(), otherOpen, blocking, settings.getPaymentClockSkew()));

        if (event.getRestaurantId() == null) event.link(tenant, purchaseId, attemptId);
        switch (decision.kind()) {
            case ALREADY_SETTLED -> {
                event.processed(now);
                events.flush();
                return new Result(Outcome.PROCESSED, purchaseId);
            }
            case PAY -> {
                PurchaseStatus before = purchase.getStatus();
                attempt.markSuccess(event.getId(), event.getOccurredAt(), now);
                attempts.flush();
                attempts.endPending(tenant, purchaseId, "CANCELLED", now);
                purchase.markPaid(event.getId(), event.getOccurredAt(), now);
                event.processed(now);
                events.flush();
                audits.record(tenant, null, "SUBSCRIPTION_PURCHASE_PAID", "subscription_purchases", purchaseId,
                    Map.of("status", before.name()), Map.of("status", "PAID", "paymentEventId", event.getId().toString(),
                        "attemptId", attemptId.toString(), "amount", event.getAmount().longValueExact(), "paidAt", event.getOccurredAt().toString()), null);
                return new Result(Outcome.PAID, purchaseId);
            }
            default -> {
                if (decision.reviewPurchase() && purchase.getStatus() == PurchaseStatus.PENDING_PAYMENT && !purchase.isDue(now)) {
                    attempts.endPending(tenant, purchaseId, "CANCELLED", now);
                    purchase.markReview(decision.reason().name(), now);
                } else if (purchase.isDue(now)) {
                    expiration.expireIfDue(purchase, now);
                }
                event.review(decision.reason(), now);
                events.flush();
                auditReview(event, decision.reason());
                return new Result(Outcome.REVIEW, purchaseId);
            }
        }
    }

    private void auditReview(SubscriptionPaymentEvent event, ReviewReason reason) {
        Map<String, Object> after = new HashMap<>();
        after.put("state", "REVIEW_REQUIRED"); after.put("reviewReason", reason.name());
        if (event.getPurchaseId() != null) after.put("purchaseId", event.getPurchaseId().toString());
        audits.record(event.getRestaurantId(), null, "SUBSCRIPTION_PAYMENT_REVIEW_REQUIRED", "subscription_payment_events",
            event.getId(), null, after, null);
    }

    private static String text(SubscriptionPaymentEvent event, String field) {
        Object value = event.getPayload() == null ? null : event.getPayload().get(field);
        return value == null ? null : value.toString();
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
