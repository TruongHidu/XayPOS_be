package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.subscriptionpurchase.entity.PaymentEventState;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pure rules for an operator confirming that a reviewed bank transfer pays for a purchase.
 * Mirrors the V41 transition trigger: only unpaid PENDING_PAYMENT, EXPIRED or REVIEW_REQUIRED purchases may become PAID.
 */
@Component
public class ManualPaymentAcceptancePolicy {
    private static final Set<PurchaseStatus> PAYABLE = Set.of(PurchaseStatus.PENDING_PAYMENT, PurchaseStatus.EXPIRED,
        PurchaseStatus.REVIEW_REQUIRED);

    public record Facts(
        PaymentEventState eventState, String direction, UUID eventPurchaseId, UUID targetPurchaseId,
        PurchaseStatus purchaseStatus, UUID purchasePaidEventId, boolean otherOpenPurchase, boolean purchaseHasAttempt
    ) {}

    /** Resolves which purchase the transfer pays for before any lock is taken. */
    public UUID targetPurchase(UUID eventPurchaseId, UUID requestedPurchaseId) {
        if (eventPurchaseId == null && requestedPurchaseId == null)
            throw new ConflictException("PURCHASE_REQUIRED", "Choose the purchase this transfer pays for");
        if (eventPurchaseId != null && requestedPurchaseId != null && !eventPurchaseId.equals(requestedPurchaseId))
            throw linkedElsewhere();
        return eventPurchaseId != null ? eventPurchaseId : requestedPurchaseId;
    }

    public void check(Facts f) {
        if (f.eventState() != PaymentEventState.REVIEW_REQUIRED)
            throw new ConflictException("PAYMENT_EVENT_NOT_IN_REVIEW", "The transfer is not waiting for review (" + f.eventState() + ")");
        if (!"in".equals(f.direction()))
            throw new ConflictException("PAYMENT_EVENT_NOT_INCOMING", "Only incoming transfers can pay for a purchase");
        if (f.eventPurchaseId() != null && !f.eventPurchaseId().equals(f.targetPurchaseId())) throw linkedElsewhere();
        if (f.purchasePaidEventId() != null)
            throw new ConflictException("PURCHASE_ALREADY_PAID", "The purchase already has a payment");
        if (!PAYABLE.contains(f.purchaseStatus()))
            throw new ConflictException("PURCHASE_NOT_PAYABLE", "The purchase can no longer be paid (" + f.purchaseStatus() + ")");
        if (f.purchaseStatus() == PurchaseStatus.EXPIRED && f.otherOpenPurchase())
            throw new ConflictException("OPEN_PURCHASE_EXISTS", "The restaurant has another open purchase");
        if (f.eventPurchaseId() == null && !f.purchaseHasAttempt())
            throw new ConflictException("PURCHASE_HAS_NO_ATTEMPT", "The purchase never generated a payment QR");
    }

    private static ConflictException linkedElsewhere() {
        return new ConflictException("PAYMENT_EVENT_LINKED_ELSEWHERE", "This transfer already belongs to another purchase");
    }
}
