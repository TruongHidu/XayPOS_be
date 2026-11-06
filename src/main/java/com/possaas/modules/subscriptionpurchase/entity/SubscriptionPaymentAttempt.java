package com.possaas.modules.subscriptionpurchase.entity;

import com.possaas.modules.subscriptionpurchase.application.port.PreparedPaymentInstructions;
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

/** Financial fields are immutable after INSERT (V41 trigger); lifecycle changes go through purchase commands. */
@Entity @Table(name = "subscription_payment_attempts") @Getter @NoArgsConstructor
public class SubscriptionPaymentAttempt implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "purchase_id", nullable = false, updatable = false) private UUID purchaseId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AttemptStatus status;
    @Column(nullable = false, updatable = false, length = 20) private String provider;
    @Column(name = "provider_account_key", nullable = false, updatable = false, length = 100) private String providerAccountKey;
    @Column(name = "reference_code", nullable = false, updatable = false, length = 40) private String referenceCode;
    @Column(nullable = false, updatable = false, precision = 14, scale = 2) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "char(3)")
    private String currencyCode;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> instructions;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100) private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "creation_response", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> creationResponse;
    @Column(name = "successful_event_id") private UUID successfulEventId;
    @Column(name = "paid_at") private Instant paidAt;
    @Column(name = "failure_code", length = 60) private String failureCode;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    @Transient private boolean newRecord = true;

    /** Expiry is the purchase quote expiry; an attempt never extends the payable window. */
    public static SubscriptionPaymentAttempt create(UUID id, SubscriptionPurchase purchase, String referenceCode,
        PreparedPaymentInstructions prepared, String key, String hash, Map<String, Object> response, Instant now) {
        SubscriptionPaymentAttempt attempt = new SubscriptionPaymentAttempt();
        attempt.id = id; attempt.restaurantId = purchase.getRestaurantId(); attempt.purchaseId = purchase.getId();
        attempt.status = AttemptStatus.PENDING; attempt.provider = prepared.provider();
        attempt.providerAccountKey = prepared.providerAccountKey(); attempt.referenceCode = referenceCode;
        attempt.amount = purchase.getAmount(); attempt.currencyCode = purchase.getCurrencyCode();
        attempt.expiresAt = purchase.getExpiresAt(); attempt.instructions = prepared.instructions().toMap();
        attempt.idempotencyKey = key; attempt.requestHash = hash; attempt.creationResponse = response;
        attempt.createdAt = now; attempt.updatedAt = now;
        return attempt;
    }

    /** Allowed from PENDING, EXPIRED (timely money, late webhook) and FAILED; never from CANCELLED. */
    public boolean canSettle() {
        return status == AttemptStatus.PENDING || status == AttemptStatus.EXPIRED || status == AttemptStatus.FAILED;
    }

    public void markSuccess(UUID eventId, Instant paidAt, Instant now) {
        status = AttemptStatus.SUCCESS; successfulEventId = eventId; this.paidAt = paidAt; updatedAt = now;
    }

    public boolean isDue(Instant now) { return status == AttemptStatus.PENDING && !expiresAt.isAfter(now); }
    public AttemptStatus displayStatus(Instant now) { return isDue(now) ? AttemptStatus.EXPIRED : status; }
    @Override public boolean isNew() { return newRecord; }
    @PostLoad @PostPersist private void persisted() { newRecord = false; }
}
