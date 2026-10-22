package com.possaas.modules.inventory.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One shortage episode. Trigger metadata is never rewritten by lifecycle transitions. */
@Entity @Table(name="low_stock_alerts") @Getter @NoArgsConstructor
public class LowStockAlert {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="restaurant_id",nullable=false,updatable=false) private UUID restaurantId;
    @Column(name="warehouse_id",nullable=false,updatable=false) private UUID warehouseId;
    @Column(name="item_id",nullable=false,updatable=false) private UUID itemId;
    @Column(name="warehouse_code",nullable=false,updatable=false,length=50) private String warehouseCode;
    @Column(name="warehouse_name",nullable=false,updatable=false,length=100) private String warehouseName;
    @Column(name="ingredient_sku",updatable=false,length=80) private String ingredientSku;
    @Column(name="ingredient_name",nullable=false,updatable=false,length=150) private String ingredientName;
    @Column(nullable=false,updatable=false,length=30) private String unit;
    @Column(name="quantity_at_trigger",nullable=false,updatable=false,precision=14,scale=3) private BigDecimal quantityAtTrigger;
    @Column(name="threshold_quantity",nullable=false,updatable=false,precision=14,scale=3) private BigDecimal thresholdQuantity;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private LowStockAlertStatus status;
    @Column(name="triggered_at",nullable=false,updatable=false) private Instant triggeredAt;
    @Column(name="acknowledged_by") private UUID acknowledgedBy;
    @Column(name="acknowledged_at") private Instant acknowledgedAt;
    @Column(name="resolved_at") private Instant resolvedAt;
    @Enumerated(EnumType.STRING) @Column(name="resolution_reason",length=40) private LowStockAlertResolutionReason resolutionReason;
    @Version private long version;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    public LowStockAlert(UUID tenant, UUID warehouse, UUID item, String warehouseCode, String warehouseName,
            String sku, String name, String unit, BigDecimal quantity, BigDecimal threshold, Instant now) {
        restaurantId=tenant; warehouseId=warehouse; itemId=item; this.warehouseCode=warehouseCode;
        this.warehouseName=warehouseName; ingredientSku=sku; ingredientName=name; this.unit=unit;
        quantityAtTrigger=quantity; thresholdQuantity=threshold; status=LowStockAlertStatus.OPEN;
        triggeredAt=now.truncatedTo(ChronoUnit.MICROS); createdAt=triggeredAt; updatedAt=triggeredAt;
    }

    public void acknowledge(UUID actor, Instant now) {
        if (status != LowStockAlertStatus.OPEN) throw new IllegalStateException("Alert must be OPEN");
        status=LowStockAlertStatus.ACKNOWLEDGED; acknowledgedBy=actor;
        acknowledgedAt=now.truncatedTo(ChronoUnit.MICROS); updatedAt=acknowledgedAt;
    }

    public void resolve(LowStockAlertResolutionReason reason, Instant now) {
        if (status == LowStockAlertStatus.RESOLVED) throw new IllegalStateException("Alert already resolved");
        status=LowStockAlertStatus.RESOLVED; resolutionReason=reason;
        resolvedAt=now.truncatedTo(ChronoUnit.MICROS); updatedAt=resolvedAt;
    }
}
