package com.possaas.modules.subscriptionpurchase.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.possaas.modules.subscriptionpurchase.entity.AttemptStatus;
import java.time.Instant;
import java.util.UUID;

public final class PaymentAttemptResponses {
    private PaymentAttemptResponses() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID id, UUID purchaseId, AttemptStatus status, AttemptStatus displayStatus, String provider,
        String referenceCode, long amount, String currencyCode, Instant expiresAt, PaymentInstructions instructions,
        Instant paidAt, Instant createdAt, Instant updatedAt, long version, Instant serverTime) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Current(View paymentAttempt, Instant serverTime) {}

    public record Creation(View body, boolean replayed) {}
}
