package com.possaas.modules.qrorder.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter @Setter @Entity @Table(name="qr_order_requests")
public class QrOrderRequest extends QrRecord {
    @Column(name="guest_authorization_id",nullable=false,updatable=false) private UUID guestAuthorizationId;
    @Column(name="guest_id",nullable=false,updatable=false) private UUID guestId;
    @Enumerated(EnumType.STRING) @Column(name="entry_type",nullable=false,updatable=false,length=20) private QrEntryType entryType;
    @Column(name="table_id",updatable=false) private UUID tableId;
    @Column(name="table_access_epoch",updatable=false) private Long tableAccessEpoch;
    @Column(name="credential_hash",nullable=false,updatable=false,length=64) private String credentialHash;
    @Column(name="observed_session_id",updatable=false) private UUID observedSessionId;
    @Column(name="idempotency_key",nullable=false,updatable=false,length=100) private String idempotencyKey;
    @Column(name="payload_hash",nullable=false,updatable=false,length=64) private String payloadHash;
    @Column(name="customer_name",updatable=false,length=100) private String customerName;
    @Column(name="customer_phone",updatable=false,length=30) private String customerPhone;
    @Column(updatable=false,length=500) private String note;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="currency_code",nullable=false,updatable=false,columnDefinition="char(3)") private String currencyCode;
    @Column(name="estimated_amount",nullable=false,updatable=false,precision=14,scale=2) private BigDecimal estimatedAmount;
    @Column(name="expires_at",nullable=false,updatable=false) private Instant expiresAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private QrRequestStatus status=QrRequestStatus.PENDING;
    @Version private long version;
    @Column(name="approved_order_id") private UUID approvedOrderId;
    @Column(name="approved_session_id") private UUID approvedSessionId;
    @Column(name="approved_by") private UUID approvedBy;
    @Column(name="approved_at") private Instant approvedAt;
    @Column(name="approved_amount",precision=14,scale=2) private BigDecimal approvedAmount;
    @Column(name="approved_order_code",length=50) private String approvedOrderCode;
    @Column(name="rejected_by") private UUID rejectedBy;
    @Column(name="rejected_at") private Instant rejectedAt;
    @Column(name="rejection_reason",length=1000) private String rejectionReason;
    @Column(name="cancelled_at") private Instant cancelledAt;
    @Column(name="cancellation_reason",length=100) private String cancellationReason;
    @Override public String toString() { return "QrOrderRequest[id="+getId()+", status="+status+"]"; }
}
