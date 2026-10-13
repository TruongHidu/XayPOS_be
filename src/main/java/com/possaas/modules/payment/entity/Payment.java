package com.possaas.modules.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter @Setter @Entity @Table(name = "payments")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "payment_code", nullable = false, length = 50, updatable = false) private String paymentCode;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, columnDefinition = "char(3)", updatable = false)
    private String currencyCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20, updatable = false)
    private PaymentMethod paymentMethod;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private PaymentStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_source", nullable = false, length = 30)
    private PaymentConfirmationSource confirmationSource;
    @Column(length = 50) private String provider;
    @Column(name = "provider_transaction_id", length = 150) private String providerTransactionId;
    @Column(name = "reference_no", length = 100, updatable = false) private String referenceNo;
    @Column(name = "idempotency_key", length = 100, updatable = false) private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_fingerprint", columnDefinition = "char(64)", updatable = false)
    private String requestFingerprint;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Column(name = "paid_at") private Instant paidAt;
    @Column(name = "created_by", updatable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
}
