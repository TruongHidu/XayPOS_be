package com.possaas.modules.subscriptionpurchase.entity;

import com.possaas.modules.subscriptionpurchase.dto.PurchaseQuote;
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

/** Assigned UUID permits storing a complete immutable replay response in the first INSERT. */
@Entity @Table(name = "subscription_purchases") @Getter @NoArgsConstructor
public class SubscriptionPurchase implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "created_by", nullable = false, updatable = false) private UUID createdBy;
    @Column(name = "package_id", nullable = false, updatable = false) private UUID packageId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private PurchaseStatus status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> quote;
    @Column(name = "quote_schema_version", nullable = false, updatable = false) private short quoteSchemaVersion;
    @Column(nullable = false, updatable = false, precision = 14, scale = 2) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "char(3)")
    private String currencyCode;
    @Column(name = "quoted_at", nullable = false, updatable = false) private Instant quotedAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100) private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "creation_response", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> creationResponse;
    @Column(name = "paid_at") private Instant paidAt;
    @Column(name = "paid_event_id") private UUID paidEventId;
    @Column(name = "subscription_id") private UUID subscriptionId;
    @Column(name = "activated_at") private Instant activatedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "cancel_reason", length = 500) private String cancelReason;
    @Column(name = "review_reason", length = 60) private String reviewReason;
    @Column(name = "activation_retry_count", nullable = false) private int activationRetryCount;
    @Column(name = "activation_next_at") private Instant activationNextAt;
    @Column(name = "activation_lease_until") private Instant activationLeaseUntil;
    @Column(name = "activation_token") private UUID activationToken;
    @Column(name = "activation_error_code", length = 60) private String activationErrorCode;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    @Transient private boolean newRecord = true;

    public static SubscriptionPurchase create(UUID restaurantId, UUID actorId, PurchaseQuote frozenQuote,
        String key, String hash, UUID id, Map<String, Object> response) {
        SubscriptionPurchase purchase = new SubscriptionPurchase();
        purchase.id = id; purchase.restaurantId = restaurantId; purchase.createdBy = actorId;
        purchase.packageId = frozenQuote.packageId(); purchase.status = PurchaseStatus.PENDING_PAYMENT;
        purchase.quote = frozenQuote.toMap(); purchase.quoteSchemaVersion = (short) frozenQuote.schemaVersion();
        purchase.amount = BigDecimal.valueOf(frozenQuote.priceAmount()); purchase.currencyCode = frozenQuote.currencyCode();
        purchase.quotedAt = frozenQuote.quotedAt(); purchase.expiresAt = frozenQuote.expiresAt();
        purchase.idempotencyKey = key; purchase.requestHash = hash; purchase.creationResponse = response;
        purchase.createdAt = frozenQuote.quotedAt(); purchase.updatedAt = frozenQuote.quotedAt();
        return purchase;
    }

    public boolean isDue(Instant now) { return status == PurchaseStatus.PENDING_PAYMENT && !expiresAt.isAfter(now); }
    public void expire(Instant now) { status = PurchaseStatus.EXPIRED; updatedAt = now; }
    public void cancel(Instant now, String reason) {
        status = PurchaseStatus.CANCELLED; cancelledAt = now; cancelReason = reason; updatedAt = now;
    }
    /** Verified money: paid evidence is set once and activation is scheduled immediately. */
    public void markPaid(UUID eventId, Instant paidAt, Instant now) {
        status = PurchaseStatus.PAID; paidEventId = eventId; this.paidAt = paidAt;
        activationNextAt = now; activationRetryCount = 0; updatedAt = now;
    }

    public boolean activationHeldBy(UUID token) { return status == PurchaseStatus.PAID && token.equals(activationToken); }

    public void claimActivation(UUID token, Instant leaseUntil, Instant now) {
        activationToken = token; activationLeaseUntil = leaseUntil; updatedAt = now;
    }

    public void markActivated(UUID subscriptionId, Instant now) {
        status = PurchaseStatus.ACTIVATED; this.subscriptionId = subscriptionId; activatedAt = now;
        clearActivationJob(); activationErrorCode = null; updatedAt = now;
    }

    public void scheduleActivationRetry(Instant next, String errorCode, Instant now) {
        activationRetryCount++; activationNextAt = next; activationErrorCode = errorCode;
        activationLeaseUntil = null; activationToken = null; updatedAt = now;
    }

    /** Paid evidence, if any, is kept; the open slot stays taken until an operator resolves it. */
    public void markReview(String reason, Instant now) {
        status = PurchaseStatus.REVIEW_REQUIRED; reviewReason = reason; clearActivationJob(); updatedAt = now;
    }

    /** Operator resumes activation of a reviewed purchase that already holds paid evidence. */
    public void resumeActivation(Instant now) {
        status = PurchaseStatus.PAID; activationNextAt = now; activationRetryCount = 0; activationErrorCode = null;
        activationLeaseUntil = null; activationToken = null; updatedAt = now;
    }

    /** Operator closes a reviewed purchase; frees the open-purchase slot of the restaurant. */
    public void close(Instant now) { status = PurchaseStatus.CLOSED; closedAt = now; clearActivationJob(); updatedAt = now; }

    private void clearActivationJob() { activationNextAt = null; activationLeaseUntil = null; activationToken = null; }

    @Override public boolean isNew() { return newRecord; }
    @PostLoad @PostPersist private void persisted() { newRecord = false; }
}
