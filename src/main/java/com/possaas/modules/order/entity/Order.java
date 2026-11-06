package com.possaas.modules.order.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter @Setter @Entity @Table(name="orders")
public class Order extends OrderRecord {
    @Column(name="order_code",nullable=false,length=50,updatable=false) private String orderCode;
    @Column(name="table_session_id",updatable=false) private UUID tableSessionId;
    @Enumerated(EnumType.STRING) @Column(name="service_type",nullable=false,length=20,updatable=false) private ServiceType serviceType;
    @Enumerated(EnumType.STRING) @Column(name="source_channel",nullable=false,length=20,updatable=false) private SourceChannel sourceChannel;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private OrderStatus status = OrderStatus.OPEN;
    @Enumerated(EnumType.STRING) @Column(name="payment_status",nullable=false,length=20) private OrderPaymentStatus paymentStatus = OrderPaymentStatus.UNPAID;
    @Column(name="customer_name",length=100) private String customerName;
    @Column(name="customer_phone",length=30) private String customerPhone;
    @Column(name="guest_count",nullable=false) private short guestCount = 1;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="currency_code",nullable=false,columnDefinition="char(3)",updatable=false) private String currencyCode;
    @Column(name="subtotal_amount",nullable=false,precision=14,scale=2) private BigDecimal subtotalAmount = BigDecimal.ZERO;
    @Column(name="discount_amount",nullable=false,precision=14,scale=2) private BigDecimal discountAmount = BigDecimal.ZERO;
    @Column(name="tax_amount",nullable=false,precision=14,scale=2) private BigDecimal taxAmount = BigDecimal.ZERO;
    @Column(name="service_charge_amount",nullable=false,precision=14,scale=2) private BigDecimal serviceChargeAmount = BigDecimal.ZERO;
    @Column(name="total_amount",nullable=false,precision=14,scale=2) private BigDecimal totalAmount = BigDecimal.ZERO;
    @Column(name="paid_amount",nullable=false,precision=14,scale=2) private BigDecimal paidAmount = BigDecimal.ZERO;
    private String note;
    @Column(name="cancel_reason") private String cancelReason;
    @Column(name="created_by",updatable=false) private UUID createdBy;
    @Column(name="confirmed_at") private Instant confirmedAt;
    @Column(name="completed_at") private Instant completedAt;
    @Column(name="cancelled_at") private Instant cancelledAt;
    @Version private long version;
    @Column(name="mutation_sequence",nullable=false) private long mutationSequence;
    @Column(name="idempotency_key",nullable=false,length=100,updatable=false) private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="request_hash",nullable=false,columnDefinition="char(64)",updatable=false) private String requestHash;

    public void changed(Instant now) { mutationSequence++; setUpdatedAt(now); }
}
