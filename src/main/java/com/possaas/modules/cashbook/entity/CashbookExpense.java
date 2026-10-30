package com.possaas.modules.cashbook.entity;

import com.possaas.modules.payment.entity.PaymentMethod;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter @Entity @Table(name="cashbook_expenses")
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class CashbookExpense {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="restaurant_id",nullable=false,updatable=false) private UUID restaurantId;
    @Column(name="expense_code",nullable=false,length=50,updatable=false) private String expenseCode;
    @Column(name="category_id",nullable=false,updatable=false) private UUID categoryId;
    @Column(nullable=false,precision=18,scale=2,updatable=false) private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="currency_code",nullable=false,columnDefinition="char(3)",updatable=false) private String currencyCode;
    @Enumerated(EnumType.STRING) @Column(name="payment_method",nullable=false,length=20,updatable=false) private PaymentMethod paymentMethod;
    @Column(name="expense_at",nullable=false,updatable=false) private Instant expenseAt;
    @Column(name="stock_document_id",updatable=false) private UUID stockDocumentId;
    @Column(name="source_document_code",length=50,updatable=false) private String sourceDocumentCode;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="source_currency_code",columnDefinition="char(3)",updatable=false) private String sourceCurrencyCode;
    @Column(name="reference_no",length=100,updatable=false) private String referenceNo;
    @Column(name="payee_name",length=150,updatable=false) private String payeeName;
    @Column(updatable=false) private String note;
    @Column(nullable=false,length=10) private String status;
    @Column(name="idempotency_key",nullable=false,length=100,updatable=false) private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="request_fingerprint",nullable=false,columnDefinition="char(64)",updatable=false) private String requestFingerprint;
    @Column(name="created_by",nullable=false,updatable=false) private UUID createdBy;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Version private long version;
    @Column(name="voided_by") private UUID voidedBy;
    @Column(name="voided_at") private Instant voidedAt;
    @Column(name="void_reason",length=1000) private String voidReason;

    public CashbookExpense(UUID restaurantId,UUID categoryId,BigDecimal amount,String currencyCode,
            PaymentMethod method,Instant expenseAt,UUID stockDocumentId,String sourceDocumentCode,
            String sourceCurrencyCode,String referenceNo,String payeeName,String note,String key,
            String fingerprint,UUID actor,Instant now) {
        this.restaurantId=restaurantId; this.categoryId=categoryId; this.amount=amount;
        this.currencyCode=currencyCode; this.paymentMethod=method; this.expenseAt=expenseAt;
        this.stockDocumentId=stockDocumentId;this.sourceDocumentCode=sourceDocumentCode;this.sourceCurrencyCode=sourceCurrencyCode;
        this.referenceNo=referenceNo;this.payeeName=payeeName;this.note=note;this.idempotencyKey=key;
        this.requestFingerprint=fingerprint;this.createdBy=actor;this.createdAt=now;this.updatedAt=now;
        this.status="POSTED";this.expenseCode="EXP-"+UUID.randomUUID().toString().replace("-","").toUpperCase(java.util.Locale.ROOT);
    }
    public void voidExpense(UUID actor,String reason,Instant now) {
        if (!"POSTED".equals(status)) throw new IllegalStateException("Only a posted expense can be voided");
        status="VOIDED";voidedBy=actor;voidReason=reason;voidedAt=now;updatedAt=now;
    }
}
