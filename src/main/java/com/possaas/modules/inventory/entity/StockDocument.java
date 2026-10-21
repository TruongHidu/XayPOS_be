package com.possaas.modules.inventory.entity;

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
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "stock_documents")
public class StockDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Column(name = "document_code", nullable = false, length = 50)
    private String documentCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private StockDocumentType documentType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockDocumentStatus status;
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;
    @Column(name = "creation_request_hash", nullable = false, length = 64)
    private String creationRequestHash;
    private String note;
    @Column(name = "line_count", nullable = false)
    private int lineCount;
    @Column(name = "total_quantity", nullable = false, precision = 16, scale = 3)
    private BigDecimal totalQuantity = new BigDecimal("0.000");
    @Column(name = "total_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalCost = new BigDecimal("0.00");
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
    @Column(name = "confirmed_by")
    private UUID confirmedBy;
    @Column(name = "cancelled_by")
    private UUID cancelledBy;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void initialize(UUID restaurantId, UUID warehouseId, String documentCode,
            StockDocumentType documentType, String idempotencyKey, String creationRequestHash,
            String note, UUID createdBy, Instant now) {
        Instant timestamp = now.truncatedTo(ChronoUnit.MICROS);
        this.restaurantId = restaurantId;
        this.warehouseId = warehouseId;
        this.documentCode = documentCode;
        this.documentType = documentType;
        this.idempotencyKey = idempotencyKey;
        this.creationRequestHash = creationRequestHash;
        this.note = note;
        this.createdBy = createdBy;
        this.status = StockDocumentStatus.DRAFT;
        this.lineCount = 0;
        this.createdAt = timestamp;
        this.updatedAt = timestamp;
    }

    public void updateNote(String value, Instant now) { note = value; touch(now); }

    public void updateTotals(int count, BigDecimal quantity, BigDecimal cost, Instant now) {
        lineCount = count;
        totalQuantity = quantity;
        totalCost = cost;
        touch(now);
    }

    public void confirm(UUID actorId, Instant now) {
        status = StockDocumentStatus.CONFIRMED;
        confirmedBy = actorId;
        confirmedAt = now.truncatedTo(ChronoUnit.MICROS);
        touch(now);
    }

    public void cancel(UUID actorId, Instant now) {
        status = StockDocumentStatus.CANCELLED;
        cancelledBy = actorId;
        cancelledAt = now.truncatedTo(ChronoUnit.MICROS);
        touch(now);
    }

    public void touch(Instant now) { updatedAt = now.truncatedTo(ChronoUnit.MICROS); }
}
