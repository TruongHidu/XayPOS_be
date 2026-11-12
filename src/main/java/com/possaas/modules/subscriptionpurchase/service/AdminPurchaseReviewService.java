package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.ClosePurchaseRequest;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseDetail;
import com.possaas.modules.subscriptionpurchase.entity.PaymentEventState;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentAttemptRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentEventRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator decisions on a purchase parked in REVIEW_REQUIRED. */
@Service
public class AdminPurchaseReviewService {
    private final SubscriptionPurchaseRepository purchases;
    private final SubscriptionPaymentAttemptRepository attempts;
    private final SubscriptionPaymentEventRepository events;
    private final RestaurantRepository restaurants;
    private final PurchaseActivationTrigger activation;
    private final AdminPurchaseQueryService queries;
    private final AuditService audits;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AdminPurchaseReviewService(SubscriptionPurchaseRepository purchases, SubscriptionPaymentAttemptRepository attempts,
        SubscriptionPaymentEventRepository events, RestaurantRepository restaurants, PurchaseActivationTrigger activation,
        AdminPurchaseQueryService queries, AuditService audits, Clock clock, PlatformTransactionManager transactions) {
        this.purchases = purchases; this.attempts = attempts; this.events = events; this.restaurants = restaurants;
        this.activation = activation; this.queries = queries; this.audits = audits; this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Ends the purchase so the restaurant can buy again; any refund happens outside the system. */
    public PurchaseDetail close(UUID purchaseId, ClosePurchaseRequest request, UUID actorId, String ip) {
        withLockedPurchase(purchaseId, purchase -> {
            if (purchase.getStatus() != PurchaseStatus.REVIEW_REQUIRED)
                throw new ConflictException("PURCHASE_NOT_IN_REVIEW", "Only purchases waiting for review can be closed");
            Instant now = now();
            String note = request.note().trim();
            attempts.endPending(purchase.getRestaurantId(), purchaseId, "CANCELLED", now);
            purchase.close(now);
            purchases.flush();
            audits.record(purchase.getRestaurantId(), actorId, "SUBSCRIPTION_PURCHASE_CLOSED", "subscription_purchases", purchaseId,
                before(purchase.getReviewReason()), Map.of("status", "CLOSED", "note", note,
                    "moneyReceived", purchase.getPaidEventId() != null), ip);
        });
        return queries.detail(purchaseId);
    }

    /** Paid purchase whose blocker (e.g. a conflicting subscription) was cleared: try activation again. */
    public PurchaseDetail resumeActivation(UUID purchaseId, UUID actorId, String ip) {
        withLockedPurchase(purchaseId, purchase -> {
            if (purchase.getStatus() != PurchaseStatus.REVIEW_REQUIRED || purchase.getPaidEventId() == null)
                throw new ConflictException("PURCHASE_NOT_RESUMABLE", "Only paid purchases waiting for review can be activated again");
            Instant now = now();
            String reason = purchase.getReviewReason();
            purchase.resumeActivation(now);
            purchases.flush();
            events.findForUpdate(purchase.getPaidEventId()).filter(e -> e.getState() == PaymentEventState.REVIEW_REQUIRED)
                .ifPresent(e -> { e.requeue(now); events.flush(); });
            audits.record(purchase.getRestaurantId(), actorId, "SUBSCRIPTION_PURCHASE_ACTIVATION_RESUMED", "subscription_purchases",
                purchaseId, before(reason), Map.of("status", "PAID"), ip);
        });
        activation.activateAfterCommit(purchaseId);
        return queries.detail(purchaseId);
    }

    private void withLockedPurchase(UUID purchaseId, Consumer<SubscriptionPurchase> action) {
        UUID tenant = purchases.findRestaurantId(purchaseId).orElseThrow(AdminPurchaseReviewService::purchaseNotFound);
        transaction.executeWithoutResult(tx -> {
            restaurants.findByIdForUpdate(tenant);
            action.accept(purchases.findForUpdate(purchaseId, tenant).orElseThrow(AdminPurchaseReviewService::purchaseNotFound));
        });
    }

    private static Map<String, Object> before(String reviewReason) {
        return Map.of("status", "REVIEW_REQUIRED", "reviewReason", String.valueOf(reviewReason));
    }

    private static ResourceNotFoundException purchaseNotFound() {
        return new ResourceNotFoundException("PURCHASE_NOT_FOUND", "Purchase not found");
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
