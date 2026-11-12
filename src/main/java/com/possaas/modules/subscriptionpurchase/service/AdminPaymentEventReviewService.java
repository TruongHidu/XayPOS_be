package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.AcceptEventRequest;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.CloseEventRequest;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PaymentEventSummary;
import com.possaas.modules.subscriptionpurchase.entity.PaymentEventState;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPaymentAttempt;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPaymentEvent;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentAttemptRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentEventRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator decisions on one bank transfer that the worker parked for review.
 * Locks follow settlement order (restaurant → purchase → attempts → event); activation runs after commit.
 */
@Service
public class AdminPaymentEventReviewService {
    private final SubscriptionPaymentEventRepository events;
    private final SubscriptionPaymentAttemptRepository attempts;
    private final SubscriptionPurchaseRepository purchases;
    private final RestaurantRepository restaurants;
    private final ManualPaymentAcceptancePolicy acceptance;
    private final PurchaseActivationTrigger activation;
    private final AdminPaymentEventQueryService queries;
    private final AuditService audits;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AdminPaymentEventReviewService(SubscriptionPaymentEventRepository events, SubscriptionPaymentAttemptRepository attempts,
        SubscriptionPurchaseRepository purchases, RestaurantRepository restaurants, ManualPaymentAcceptancePolicy acceptance,
        PurchaseActivationTrigger activation, AdminPaymentEventQueryService queries, AuditService audits, Clock clock,
        PlatformTransactionManager transactions) {
        this.events = events; this.attempts = attempts; this.purchases = purchases; this.restaurants = restaurants;
        this.acceptance = acceptance; this.activation = activation; this.queries = queries; this.audits = audits;
        this.clock = clock; this.transaction = new TransactionTemplate(transactions);
    }

    /** Refunded, unrelated or settled outside the system: the transfer leaves the queue with a reason. */
    public PaymentEventSummary close(UUID eventId, CloseEventRequest request, UUID actorId, String ip) {
        transaction.executeWithoutResult(tx -> {
            SubscriptionPaymentEvent event = lockEvent(eventId);
            requireReview(event);
            String note = request.note().trim(), reference = request.resolutionReference().trim();
            event.closeReview(actorId, note, reference, now());
            events.flush();
            audits.record(event.getRestaurantId(), actorId, "SUBSCRIPTION_PAYMENT_REVIEW_CLOSED", "subscription_payment_events",
                eventId, before(event), Map.of("state", "CLOSED", "note", note, "resolutionReference", reference), ip);
        });
        return queries.detail(eventId);
    }

    /** Hands the transfer back to the worker; a purchase blocked on this paid transfer resumes activation too. */
    public PaymentEventSummary retry(UUID eventId, UUID actorId, String ip) {
        SubscriptionPaymentEvent snapshot = events.findById(eventId).orElseThrow(AdminPaymentEventReviewService::eventNotFound);
        UUID resumed = transaction.execute(tx -> {
            SubscriptionPurchase purchase = null;
            if (snapshot.getPurchaseId() != null) {
                restaurants.findByIdForUpdate(snapshot.getRestaurantId());
                purchase = purchases.findForUpdate(snapshot.getPurchaseId(), snapshot.getRestaurantId()).orElse(null);
            }
            SubscriptionPaymentEvent event = lockEvent(eventId);
            requireReview(event);
            Instant now = now();
            boolean resume = purchase != null && purchase.getStatus() == PurchaseStatus.REVIEW_REQUIRED
                && eventId.equals(purchase.getPaidEventId());
            if (resume) {
                purchase.resumeActivation(now);
                purchases.flush();
            }
            event.requeue(now);
            events.flush();
            audits.record(event.getRestaurantId(), actorId, "SUBSCRIPTION_PAYMENT_REVIEW_RETRIED", "subscription_payment_events",
                eventId, before(event), Map.of("state", "RETRY_PENDING", "purchaseActivationResumed", resume), ip);
            return resume ? purchase.getId() : null;
        });
        if (resumed != null) activation.activateAfterCommit(resumed);
        return queries.detail(eventId);
    }

    /** Operator confirms this incoming transfer pays for the purchase (e.g. small amount difference, wrong reference). */
    public PaymentEventSummary accept(UUID eventId, AcceptEventRequest request, UUID actorId, String ip) {
        SubscriptionPaymentEvent snapshot = events.findById(eventId).orElseThrow(AdminPaymentEventReviewService::eventNotFound);
        UUID purchaseId = acceptance.targetPurchase(snapshot.getPurchaseId(), request.purchaseId());
        UUID tenant = purchases.findRestaurantId(purchaseId).orElseThrow(AdminPaymentEventReviewService::purchaseNotFound);

        transaction.executeWithoutResult(tx -> {
            restaurants.findByIdForUpdate(tenant);
            SubscriptionPurchase purchase = purchases.findForUpdate(purchaseId, tenant)
                .orElseThrow(AdminPaymentEventReviewService::purchaseNotFound);
            var purchaseAttempts = attempts.lockAll(tenant, purchaseId);
            SubscriptionPaymentEvent event = lockEvent(eventId);
            acceptance.check(new ManualPaymentAcceptancePolicy.Facts(event.getState(), event.getDirection(),
                event.getPurchaseId(), purchaseId, purchase.getStatus(), purchase.getPaidEventId(),
                purchase.getStatus() == PurchaseStatus.EXPIRED
                    && purchases.findOpen(tenant).filter(p -> !p.getId().equals(purchaseId)).isPresent(),
                !purchaseAttempts.isEmpty()));
            if (event.getRestaurantId() == null) {
                SubscriptionPaymentAttempt latest = purchaseAttempts.stream()
                    .max(Comparator.comparing(SubscriptionPaymentAttempt::getCreatedAt)).orElseThrow();
                event.link(tenant, purchaseId, latest.getId());
            }
            Instant now = now();
            PurchaseStatus before = purchase.getStatus();
            String note = request.note().trim();
            Map<String, Object> eventBefore = before(event);
            attempts.endPending(tenant, purchaseId, "CANCELLED", now);
            event.closeReview(actorId, note, "MANUAL_ACCEPT:" + purchaseId, now);
            events.flush();
            purchase.markPaid(event.getId(), event.getOccurredAt(), now);
            purchases.flush();
            Map<String, Object> paid = new HashMap<>();
            paid.put("status", "PAID"); paid.put("paymentEventId", eventId.toString()); paid.put("manual", true);
            paid.put("amount", event.getAmount().longValueExact()); paid.put("note", note);
            audits.record(tenant, actorId, "SUBSCRIPTION_PURCHASE_PAID", "subscription_purchases", purchaseId,
                Map.of("status", before.name()), paid, ip);
            audits.record(tenant, actorId, "SUBSCRIPTION_PAYMENT_MANUALLY_ACCEPTED", "subscription_payment_events", eventId,
                eventBefore, Map.of("state", "CLOSED", "purchaseId", purchaseId.toString(), "note", note), ip);
        });
        activation.activateAfterCommit(purchaseId);
        return queries.detail(eventId);
    }

    private SubscriptionPaymentEvent lockEvent(UUID eventId) {
        return events.findForUpdate(eventId).orElseThrow(AdminPaymentEventReviewService::eventNotFound);
    }

    private static void requireReview(SubscriptionPaymentEvent event) {
        if (event.getState() != PaymentEventState.REVIEW_REQUIRED)
            throw new ConflictException("PAYMENT_EVENT_NOT_IN_REVIEW", "The transfer is not waiting for review (" + event.getState() + ")");
    }

    private static Map<String, Object> before(SubscriptionPaymentEvent event) {
        return Map.of("state", event.getState().name(), "reviewReason", String.valueOf(event.getReviewReason()));
    }

    private static ResourceNotFoundException eventNotFound() {
        return new ResourceNotFoundException("PAYMENT_EVENT_NOT_FOUND", "Payment event not found");
    }
    private static ResourceNotFoundException purchaseNotFound() {
        return new ResourceNotFoundException("PURCHASE_NOT_FOUND", "Purchase not found");
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
