package com.possaas.modules.subscriptionpurchase.service;

import static org.assertj.core.api.Assertions.*;

import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.ReviewReason;
import com.possaas.modules.subscriptionpurchase.service.SettlementPolicy.Decision;
import com.possaas.modules.subscriptionpurchase.service.SettlementPolicy.Facts;
import com.possaas.modules.subscriptionpurchase.service.SettlementPolicy.Kind;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementPolicyTest {
    private static final Instant CREATED = Instant.parse("2026-11-02T03:00:00Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(900);
    private static final UUID EVENT = UUID.randomUUID();
    private final SettlementPolicy policy = new SettlementPolicy();

    @Test void exactTimelyIncomingPaymentPays() {
        assertThat(policy.decide(facts().build()).kind()).isEqualTo(Kind.PAY);
        assertThat(policy.decide(facts().purchase(PurchaseStatus.EXPIRED).build()).kind()).isEqualTo(Kind.PAY);
        assertThat(policy.decide(facts().subAccount("0123456789").account("9999").build()).kind()).isEqualTo(Kind.PAY);
    }

    @Test void sameEventIsAlreadySettledButAnotherEventIsDuplicate() {
        assertThat(policy.decide(facts().purchase(PurchaseStatus.ACTIVATED).paidEvent(EVENT).build()).kind()).isEqualTo(Kind.ALREADY_SETTLED);
        assertThat(review(facts().purchase(PurchaseStatus.ACTIVATED).paidEvent(UUID.randomUUID()))).isEqualTo(new Decision(Kind.REVIEW, ReviewReason.DUPLICATE_PAYMENT, false));
        assertThat(review(facts().purchase(PurchaseStatus.PAID).paidEvent(UUID.randomUUID())).reason()).isEqualTo(ReviewReason.DUPLICATE_PAYMENT);
    }

    @Test void moneyChecksReviewAndStopUnpaidPurchase() {
        assertThat(review(facts().direction("out")).reason()).isEqualTo(ReviewReason.OUTGOING_TRANSFER);
        assertThat(review(facts().account("9999"))).isEqualTo(new Decision(Kind.REVIEW, ReviewReason.ACCOUNT_MISMATCH, true));
        assertThat(review(facts().trusted(Optional.empty())).reason()).isEqualTo(ReviewReason.ACCOUNT_MISMATCH);
        assertThat(review(facts().amount("398999"))).isEqualTo(new Decision(Kind.REVIEW, ReviewReason.AMOUNT_MISMATCH, true));
        assertThat(review(facts().amount("399001").purchase(PurchaseStatus.CANCELLED))).isEqualTo(new Decision(Kind.REVIEW, ReviewReason.AMOUNT_MISMATCH, false));
    }

    @Test void windowToleratesClockSkewOnBothEnds() {
        assertThat(policy.decide(facts().occurred(CREATED.minusSeconds(120)).build()).kind()).isEqualTo(Kind.PAY);
        assertThat(review(facts().occurred(CREATED.minusSeconds(121))).reason()).isEqualTo(ReviewReason.PAYMENT_OUTSIDE_WINDOW);
        assertThat(policy.decide(facts().occurred(EXPIRES.plusSeconds(119)).build()).kind()).isEqualTo(Kind.PAY);
        assertThat(review(facts().occurred(EXPIRES.plusSeconds(120))).reason()).isEqualTo(ReviewReason.PAYMENT_OUTSIDE_WINDOW);
    }

    @Test void purchaseStateBlocksAutomaticPayment() {
        assertThat(review(facts().purchase(PurchaseStatus.CANCELLED)).reason()).isEqualTo(ReviewReason.PURCHASE_CANCELLED);
        assertThat(review(facts().purchase(PurchaseStatus.REVIEW_REQUIRED).reviewReason("AMOUNT_MISMATCH")).reason()).isEqualTo(ReviewReason.AMOUNT_MISMATCH);
        assertThat(review(facts().purchase(PurchaseStatus.EXPIRED).otherOpen(true)).reason()).isEqualTo(ReviewReason.SUBSCRIPTION_CONFLICT);
        assertThat(review(facts().purchase(PurchaseStatus.EXPIRED).blocking(true)).reason()).isEqualTo(ReviewReason.SUBSCRIPTION_CONFLICT);
        assertThat(review(facts().settleable(false)).reason()).isEqualTo(ReviewReason.PURCHASE_CANCELLED);
    }

    private Decision review(Builder builder) {
        Decision decision = policy.decide(builder.build());
        assertThat(decision.kind()).isEqualTo(Kind.REVIEW);
        return decision;
    }

    private static Builder facts() { return new Builder(); }

    private static final class Builder {
        String direction = "in", account = "0123456789", subAccount, reviewReason;
        BigDecimal amount = new BigDecimal("399000");
        Instant occurred = CREATED.plusSeconds(60);
        Optional<String> trusted = Optional.of("0123456789");
        boolean settleable = true, otherOpen, blocking;
        PurchaseStatus purchase = PurchaseStatus.PENDING_PAYMENT;
        UUID paidEvent;
        Builder direction(String v) { direction = v; return this; }
        Builder account(String v) { account = v; return this; }
        Builder subAccount(String v) { subAccount = v; return this; }
        Builder amount(String v) { amount = new BigDecimal(v); return this; }
        Builder occurred(Instant v) { occurred = v; return this; }
        Builder trusted(Optional<String> v) { trusted = v; return this; }
        Builder settleable(boolean v) { settleable = v; return this; }
        Builder purchase(PurchaseStatus v) { purchase = v; return this; }
        Builder paidEvent(UUID v) { paidEvent = v; return this; }
        Builder reviewReason(String v) { reviewReason = v; return this; }
        Builder otherOpen(boolean v) { otherOpen = v; return this; }
        Builder blocking(boolean v) { blocking = v; return this; }
        Facts build() {
            return new Facts(EVENT, direction, amount, "VND", account, subAccount, occurred, trusted, new BigDecimal("399000"),
                settleable, CREATED, EXPIRES, purchase, paidEvent, reviewReason, otherOpen, blocking, Duration.ofMinutes(2));
        }
    }
}
