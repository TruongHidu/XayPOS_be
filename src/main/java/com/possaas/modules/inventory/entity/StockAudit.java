package com.possaas.modules.inventory.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="stock_audits") @Getter @NoArgsConstructor
public class StockAudit {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="restaurant_id",nullable=false,updatable=false) private UUID restaurantId;
    @Column(name="warehouse_id",nullable=false,updatable=false) private UUID warehouseId;
    @Column(name="audit_code",nullable=false,updatable=false,length=50) private String auditCode;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private StockAuditStatus status;
    private String note;
    @Column(name="idempotency_key",nullable=false,updatable=false,length=100) private String idempotencyKey;
    @Column(name="creation_request_hash",nullable=false,updatable=false,length=64) private String creationRequestHash;
    @Column(name="created_by",nullable=false,updatable=false) private UUID createdBy;
    @Column(name="started_by") private UUID startedBy;
    @Column(name="confirmed_by") private UUID confirmedBy;
    @Column(name="cancelled_by") private UUID cancelledBy;
    @Column(name="started_at") private Instant startedAt;
    @Column(name="confirmed_at") private Instant confirmedAt;
    @Column(name="cancelled_at") private Instant cancelledAt;
    @Column(name="count_revision",nullable=false) private long countRevision;
    @Version private long version;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    public StockAudit(UUID tenant,UUID warehouse,String code,String key,String hash,String note,UUID actor,Instant now) {
        restaurantId=tenant;warehouseId=warehouse;auditCode=code;idempotencyKey=key;creationRequestHash=hash;
        this.note=note;createdBy=actor;status=StockAuditStatus.DRAFT;createdAt=micros(now);updatedAt=createdAt;
    }
    public void updateNote(String value,Instant now) {note=value;updatedAt=micros(now);}
    public void start(UUID actor,Instant now) {status=StockAuditStatus.COUNTING;startedBy=actor;startedAt=micros(now);updatedAt=startedAt;}
    public void counted(Instant now) {countRevision++;updatedAt=micros(now);}
    public void confirm(UUID actor,Instant now) {status=StockAuditStatus.CONFIRMED;confirmedBy=actor;confirmedAt=micros(now);updatedAt=confirmedAt;}
    public void cancel(UUID actor,String note,Instant now) {status=StockAuditStatus.CANCELLED;cancelledBy=actor;cancelledAt=micros(now);updatedAt=cancelledAt;if(note!=null)this.note=note;}
    private static Instant micros(Instant value) {return value.truncatedTo(ChronoUnit.MICROS);}
}
