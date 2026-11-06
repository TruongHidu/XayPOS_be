package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.ReviewReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Pure decision for one verified transaction against the attempt its reference points to; never touches persistence. */
@Component
public class SettlementPolicy {
    public enum Kind { PAY, REVIEW, ALREADY_SETTLED }

    /** reviewPurchase: the still-unpaid purchase must stop inviting transfers and wait for an operator. */
    public record Decision(Kind kind, ReviewReason reason, boolean reviewPurchase) {
        static Decision pay() { return new Decision(Kind.PAY, null, false); }
        static Decision settled() { return new Decision(Kind.ALREADY_SETTLED, null, false); }
        static Decision review(ReviewReason reason) { return new Decision(Kind.REVIEW, reason, false); }
        static Decision reviewWithPurchase(ReviewReason reason) { return new Decision(Kind.REVIEW, reason, true); }
    }

    public record Facts(
        UUID eventId, String direction, BigDecimal amount, String currencyCode, String receiverAccount,
        String receiverSubAccount, Instant occurredAt, Optional<String> trustedAccount,
        BigDecimal attemptAmount, boolean attemptSettleable, Instant attemptCreatedAt, Instant attemptExpiresAt,
        PurchaseStatus purchaseStatus, UUID purchasePaidEventId, String purchaseReviewReason,
        boolean otherOpenPurchase, boolean blockingSubscription, Duration clockSkew
    ) {}

    public Decision decide(Facts f) {
        if (f.eventId().equals(f.purchasePaidEventId())) return Decision.settled();
        boolean unpaid = f.purchaseStatus() == PurchaseStatus.PENDING_PAYMENT;
        if (!"in".equals(f.direction())) return Decision.review(ReviewReason.OUTGOING_TRANSFER);
        if (f.trustedAccount().isEmpty() || !(f.trustedAccount().get().equals(f.receiverAccount())
            || f.trustedAccount().get().equals(f.receiverSubAccount())))
            return unpaid ? Decision.reviewWithPurchase(ReviewReason.ACCOUNT_MISMATCH) : Decision.review(ReviewReason.ACCOUNT_MISMATCH);
        if (!"VND".equals(f.currencyCode()) || f.amount().compareTo(f.attemptAmount()) != 0)
            return unpaid ? Decision.reviewWithPurchase(ReviewReason.AMOUNT_MISMATCH) : Decision.review(ReviewReason.AMOUNT_MISMATCH);
        if (f.occurredAt().isBefore(f.attemptCreatedAt().minus(f.clockSkew()))
            || !f.occurredAt().isBefore(f.attemptExpiresAt().plus(f.clockSkew())))
            return Decision.review(ReviewReason.PAYMENT_OUTSIDE_WINDOW);
        if (f.purchasePaidEventId() != null) return Decision.review(ReviewReason.DUPLICATE_PAYMENT);
        return switch (f.purchaseStatus()) {
            case CANCELLED -> Decision.review(ReviewReason.PURCHASE_CANCELLED);
            case REVIEW_REQUIRED, CLOSED -> Decision.review(reviewReason(f.purchaseReviewReason()));
            case EXPIRED -> f.otherOpenPurchase() || f.blockingSubscription() ? Decision.review(ReviewReason.SUBSCRIPTION_CONFLICT)
                : f.attemptSettleable() ? Decision.pay() : Decision.review(ReviewReason.PURCHASE_CANCELLED);
            case PENDING_PAYMENT -> f.attemptSettleable() ? Decision.pay() : Decision.review(ReviewReason.PURCHASE_CANCELLED);
            case PAID, ACTIVATED -> Decision.review(ReviewReason.DUPLICATE_PAYMENT);
        };
    }

    private static ReviewReason reviewReason(String stored) {
        try { return stored == null ? ReviewReason.SUBSCRIPTION_CONFLICT : ReviewReason.valueOf(stored); }
        catch (IllegalArgumentException unknown) { return ReviewReason.SUBSCRIPTION_CONFLICT; }
    }
}
