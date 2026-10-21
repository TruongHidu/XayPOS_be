package com.possaas.modules.inventory.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter @Entity @Table(name = "order_item_inventory_allocations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemInventoryAllocation {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "order_item_id", nullable = false, updatable = false) private UUID orderItemId;
    @Column(name = "recipe_snapshot_id", updatable = false) private UUID recipeSnapshotId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private InventoryAllocationStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "skip_reason", length = 40, updatable = false)
    private InventoryAllocationSkipReason skipReason;
    @Column(name = "warehouse_id", updatable = false) private UUID warehouseId;
    @Column(name = "order_quantity_snapshot", nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal orderQuantitySnapshot;
    @Column(name = "order_unit_snapshot", nullable = false, length = 30, updatable = false) private String orderUnitSnapshot;
    @Column(name = "reserved_at", updatable = false) private Instant reservedAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Column(name = "released_at") private Instant releasedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    public OrderItemInventoryAllocation(UUID tenant, UUID orderId, UUID itemId, UUID snapshotId,
            BigDecimal orderQuantity, String orderUnit, UUID warehouseId,
            InventoryAllocationSkipReason reason, Instant now) {
        this.restaurantId = tenant; this.orderId = orderId; this.orderItemId = itemId;
        this.recipeSnapshotId = snapshotId; this.orderQuantitySnapshot = orderQuantity;
        this.orderUnitSnapshot = orderUnit; this.warehouseId = warehouseId; this.skipReason = reason;
        this.status = reason == null ? InventoryAllocationStatus.RESERVED : InventoryAllocationStatus.SKIPPED;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS); this.updatedAt = this.createdAt;
        if (reason == null) this.reservedAt = this.createdAt;
    }

    public void consume(Instant now) {
        this.status = InventoryAllocationStatus.CONSUMED;
        this.consumedAt = now.truncatedTo(ChronoUnit.MICROS); this.updatedAt = this.consumedAt;
    }

    public void release(Instant now) {
        this.status = InventoryAllocationStatus.RELEASED;
        this.releasedAt = now.truncatedTo(ChronoUnit.MICROS); this.updatedAt = this.releasedAt;
    }
}
