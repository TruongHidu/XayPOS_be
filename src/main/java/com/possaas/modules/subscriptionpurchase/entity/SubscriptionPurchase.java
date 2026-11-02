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
    @Override public boolean isNew() { return newRecord; }
    @PostLoad @PostPersist private void persisted() { newRecord = false; }
}
