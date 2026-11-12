package com.possaas.modules.subscriptionpurchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.subscriptionpurchase.entity.PaymentEventState;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManualPaymentAcceptancePolicyTest {
    private final ManualPaymentAcceptancePolicy policy = new ManualPaymentAcceptancePolicy();
    private final UUID purchase = UUID.randomUUID();

    @Test void targetPurchaseComesFromTheLinkOrTheRequest() {
        assertThat(policy.targetPurchase(purchase, null)).isEqualTo(purchase);
        assertThat(policy.targetPurchase(null, purchase)).isEqualTo(purchase);
        assertCode(() -> policy.targetPurchase(null, null), "PURCHASE_REQUIRED");
        assertCode(() -> policy.targetPurchase(purchase, UUID.randomUUID()), "PAYMENT_EVENT_LINKED_ELSEWHERE");
    }

    @Test void acceptsReviewedIncomingTransferForUnpaidPurchase() {
        for (PurchaseStatus status : new PurchaseStatus[] {PurchaseStatus.PENDING_PAYMENT, PurchaseStatus.EXPIRED, PurchaseStatus.REVIEW_REQUIRED})
            assertThatCode(() -> policy.check(facts(PaymentEventState.REVIEW_REQUIRED, "in", purchase, status, null, false, true)))
                .doesNotThrowAnyException();
    }

    @Test void rejectsEveryUnsafeCombination() {
        assertCode(() -> policy.check(facts(PaymentEventState.CLOSED, "in", purchase, PurchaseStatus.REVIEW_REQUIRED, null, false, true)),
            "PAYMENT_EVENT_NOT_IN_REVIEW");
        assertCode(() -> policy.check(facts(PaymentEventState.REVIEW_REQUIRED, "out", purchase, PurchaseStatus.REVIEW_REQUIRED, null, false, true)),
            "PAYMENT_EVENT_NOT_INCOMING");
        assertCode(() -> policy.check(facts(PaymentEventState.REVIEW_REQUIRED, "in", UUID.randomUUID(), PurchaseStatus.REVIEW_REQUIRED, null, false, true)),
            "PAYMENT_EVENT_LINKED_ELSEWHERE");
        assertCode(() -> policy.check(facts(PaymentEventState.REVIEW_REQUIRED, "in", purchase, PurchaseStatus.REVIEW_REQUIRED, UUID.randomUUID(), false, true)),
            "PURCHASE_ALREADY_PAID");
        assertCode(() -> policy.check(facts(PaymentEventState.REVIEW_REQUIRED, "in", purchase, PurchaseStatus.CANCELLED, null, false, true)),
            "PURCHASE_NOT_PAYABLE");
        assertCode(() -> policy.check(facts(PaymentEventState.REVIEW_REQUIRED, "in", purchase, PurchaseStatus.EXPIRED, null, true, true)),
            "OPEN_PURCHASE_EXISTS");
        assertCode(() -> policy.check(facts(PaymentEventState.REVIEW_REQUIRED, "in", null, PurchaseStatus.PENDING_PAYMENT, null, false, false)),
            "PURCHASE_HAS_NO_ATTEMPT");
    }

    private ManualPaymentAcceptancePolicy.Facts facts(PaymentEventState state, String direction, UUID linked,
            PurchaseStatus status, UUID paidEvent, boolean otherOpen, boolean hasAttempt) {
        return new ManualPaymentAcceptancePolicy.Facts(state, direction, linked, purchase, status, paidEvent, otherOpen, hasAttempt);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(code);
    }
}
