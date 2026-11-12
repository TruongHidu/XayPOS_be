package com.possaas.modules.subscriptionpurchase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Super-admin read models and operator commands for SaaS package purchases and their bank transactions. */
public final class AdminPaymentDtos {
    private AdminPaymentDtos() {}

    public record RestaurantRef(UUID id, String code, String name, String status) {}

    public record PurchaseSummary(
        UUID id, RestaurantRef restaurant, String packageCode, String packageName, BigDecimal amount, String currencyCode,
        String status, String reviewReason, Instant createdAt, Instant expiresAt, Instant paidAt, Instant activatedAt,
        Instant closedAt, Instant cancelledAt, UUID subscriptionId, String activationErrorCode
    ) {}

    public record AttemptView(
        UUID id, String status, String referenceCode, BigDecimal amount, Instant createdAt, Instant expiresAt,
        Instant paidAt, String failureCode
    ) {}

    public record PaymentEventSummary(
        UUID id, String state, String reviewReason, String direction, BigDecimal amount, String currencyCode,
        Instant occurredAt, Instant receivedAt, String paymentReference, String transferContent, String bankReference,
        String receiverAccount, String receiverBank, String source, int deliveryCount, RestaurantRef restaurant,
        UUID purchaseId, String purchaseStatus, BigDecimal purchaseAmount, Instant reviewedAt, String reviewerName,
        String reviewNote, String resolutionReference, String lastErrorCode
    ) {}

    public record PurchaseDetail(
        PurchaseSummary purchase, String createdByName, String createdByEmail, String cancelReason,
        int activationRetryCount, List<AttemptView> attempts, List<PaymentEventSummary> events
    ) {}

    public record ReviewCounts(long paymentEvents, long purchases, long paidAwaitingActivation) {}

    public record CloseEventRequest(
        @NotBlank @Size(min = 10, max = 1000) String note,
        @NotBlank @Size(min = 3, max = 200) String resolutionReference
    ) {}

    /** purchaseId is required only when the bank transaction is not linked to any purchase yet. */
    public record AcceptEventRequest(
        UUID purchaseId,
        @NotBlank @Size(min = 10, max = 1000) String note
    ) {}

    public record ClosePurchaseRequest(@NotBlank @Size(min = 10, max = 1000) String note) {}
}
