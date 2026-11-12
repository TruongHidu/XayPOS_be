package com.possaas.modules.subscriptionpurchase.entity;

import com.possaas.modules.subscriptionpurchase.application.port.VerifiedPaymentEvent;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** Durable inbox row; financial evidence is immutable (V41 trigger), only processing and link columns change. */
@Entity @Table(name = "subscription_payment_events") @Getter @NoArgsConstructor
public class SubscriptionPaymentEvent implements Persistable<UUID> {
    @Id private UUID id;
    @Column(nullable = false, updatable = false, length = 20) private String provider;
    @Column(name = "provider_account_key", nullable = false, updatable = false, length = 100) private String providerAccountKey;
    @Column(name = "provider_transaction_id", nullable = false, updatable = false, length = 180) private String providerTransactionId;
    @Column(name = "provider_webhook_id") private Long providerWebhookId;
    @Column(name = "bank_reference", updatable = false, length = 150) private String bankReference;
    @Column(nullable = false, updatable = false, length = 24) private String source;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "payload_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String payloadHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;
    @Column(nullable = false, updatable = false, length = 3) private String direction;
    @Column(nullable = false, updatable = false, precision = 14, scale = 2) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "char(3)")
    private String currencyCode;
    @Column(name = "receiver_account", nullable = false, updatable = false, length = 100) private String receiverAccount;
    @Column(name = "receiver_bank", nullable = false, updatable = false, length = 100) private String receiverBank;
    @Column(name = "payment_reference", updatable = false, length = 40) private String paymentReference;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    @Column(name = "received_at", nullable = false, updatable = false) private Instant receivedAt;
    @Column(name = "restaurant_id") private UUID restaurantId;
    @Column(name = "purchase_id") private UUID purchaseId;
    @Column(name = "attempt_id") private UUID attemptId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private PaymentEventState state;
    @Enumerated(EnumType.STRING) @Column(name = "review_reason", length = 60) private ReviewReason reviewReason;
    @Column(name = "retry_count", nullable = false) private int retryCount;
    @Column(name = "delivery_count", nullable = false) private int deliveryCount;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "processing_token") private UUID processingToken;
    @Column(name = "last_received_at", nullable = false) private Instant lastReceivedAt;
    @Column(name = "processed_at") private Instant processedAt;
    @Column(name = "last_error_code", length = 60) private String lastErrorCode;
    @Column(name = "reviewed_by") private UUID reviewedBy;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "review_note", length = 1000) private String reviewNote;
    @Column(name = "resolution_reference", length = 200) private String resolutionReference;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    @Transient private boolean newRecord = true;

    public static SubscriptionPaymentEvent receive(VerifiedPaymentEvent source, String paymentReference, Instant now) {
        SubscriptionPaymentEvent event = new SubscriptionPaymentEvent();
        event.id = UUID.randomUUID(); event.provider = source.provider();
        event.providerAccountKey = source.providerAccountKey(); event.providerTransactionId = source.providerTransactionId();
        event.providerWebhookId = source.providerWebhookId(); event.bankReference = source.bankReference();
        event.source = source.source(); event.payloadHash = source.payloadHash(); event.payload = source.payload();
        event.direction = source.direction(); event.amount = BigDecimal.valueOf(source.amount());
        event.currencyCode = source.currencyCode(); event.receiverAccount = source.receiverAccount();
        event.receiverBank = source.receiverBank(); event.paymentReference = paymentReference;
        event.occurredAt = source.occurredAt(); event.receivedAt = now; event.lastReceivedAt = now;
        event.state = PaymentEventState.RECEIVED; event.deliveryCount = 1;
        event.createdAt = now; event.updatedAt = now;
        return event;
    }

    /** Duplicate delivery keeps the original evidence and any running lease. */
    public void recordDelivery(Long webhookAlias, Instant now) {
        if (providerWebhookId == null && webhookAlias != null) providerWebhookId = webhookAlias;
        deliveryCount++; lastReceivedAt = now; updatedAt = now;
    }

    public boolean sameMoney(VerifiedPaymentEvent other) {
        return amount.compareTo(BigDecimal.valueOf(other.amount())) == 0 && direction.equals(other.direction())
            && occurredAt.equals(other.occurredAt());
    }

    public boolean isClaimable() { return state == PaymentEventState.RECEIVED || state == PaymentEventState.RETRY_PENDING; }
    public boolean heldBy(UUID token) { return state == PaymentEventState.PROCESSING && token.equals(processingToken); }

    public void claim(UUID token, Instant leaseUntil, Instant now) {
        state = PaymentEventState.PROCESSING; processingToken = token; this.leaseUntil = leaseUntil; updatedAt = now;
    }

    public void link(UUID restaurantId, UUID purchaseId, UUID attemptId) {
        this.restaurantId = restaurantId; this.purchaseId = purchaseId; this.attemptId = attemptId;
    }

    public void processed(Instant now) {
        state = PaymentEventState.PROCESSED; processedAt = now; release(now);
    }

    public void review(ReviewReason reason, Instant now) {
        state = PaymentEventState.REVIEW_REQUIRED; reviewReason = reason; processedAt = now; release(now);
    }

    public void retryLater(Instant next, String errorCode, Instant now) {
        state = PaymentEventState.RETRY_PENDING; retryCount++; nextAttemptAt = next; lastErrorCode = errorCode; release(now);
    }

    /** Operator decision on a reviewed event; the V41 check requires reviewer, note and resolution reference. */
    public void closeReview(UUID reviewer, String note, String reference, Instant now) {
        state = PaymentEventState.CLOSED; reviewedBy = reviewer; reviewedAt = now;
        reviewNote = note; resolutionReference = reference; release(now);
    }

    /** Operator asks the worker to settle the event again (e.g. after a conflict was cleared). */
    public void requeue(Instant now) {
        state = PaymentEventState.RETRY_PENDING; nextAttemptAt = now; lastErrorCode = "OPERATOR_RETRY"; release(now);
    }

    private void release(Instant now) { leaseUntil = null; processingToken = null; updatedAt = now; }

    @Override public boolean isNew() { return newRecord; }
    @PostLoad @PostPersist private void persisted() { newRecord = false; }
}
