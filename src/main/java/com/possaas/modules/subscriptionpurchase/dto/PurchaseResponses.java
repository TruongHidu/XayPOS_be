package com.possaas.modules.subscriptionpurchase.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PurchaseResponses {
    private PurchaseResponses() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID id, PurchaseStatus status, PurchaseStatus displayStatus, PurchaseQuote quote,
        UUID subscriptionId, Instant paidAt, Instant activatedAt, Instant cancelledAt, Instant closedAt,
        String reviewReason, List<String> allowedActions, Instant createdAt, Instant updatedAt,
        long version, Instant serverTime) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Subscription(UUID id, String status, boolean effective, String packageCode,
        Instant startAt, Instant endAt, Instant activatedAt) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Onboarding(String subscriptionState, Subscription currentSubscription, View currentPurchase,
        long unresolvedPaymentReviewCount, boolean canPurchase, String blockedReason,
        List<String> allowedActions, Instant serverTime) {}

    public record Creation(View body, boolean replayed) {}
}
