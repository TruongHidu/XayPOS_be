package com.possaas.modules.inventory.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Getter @Entity @Immutable @Table(name = "inventory_reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryReservation {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "allocation_id", nullable = false, updatable = false) private UUID allocationId;
    @Column(name = "recipe_snapshot_id", nullable = false, updatable = false) private UUID recipeSnapshotId;
    @Column(name = "recipe_usage_id", nullable = false, updatable = false) private UUID recipeUsageId;
    @Column(name = "warehouse_id", nullable = false, updatable = false) private UUID warehouseId;
    @Column(name = "ingredient_id", nullable = false, updatable = false) private UUID ingredientId;
    @Column(nullable = false, precision = 14, scale = 3, updatable = false) private BigDecimal quantity;
    @Column(name = "ingredient_sku_snapshot", length = 80, updatable = false) private String ingredientSkuSnapshot;
    @Column(name = "ingredient_name_snapshot", nullable = false, length = 150, updatable = false) private String ingredientNameSnapshot;
    @Column(name = "ingredient_unit_snapshot", nullable = false, length = 30, updatable = false) private String ingredientUnitSnapshot;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    public InventoryReservation(OrderItemInventoryAllocation allocation, UUID usageId, UUID ingredientId,
            String sku, String name, String unit, BigDecimal quantity, Instant now) {
        this.restaurantId = allocation.getRestaurantId(); this.allocationId = allocation.getId();
        this.recipeSnapshotId = allocation.getRecipeSnapshotId(); this.recipeUsageId = usageId;
        this.warehouseId = allocation.getWarehouseId(); this.ingredientId = ingredientId;
        this.ingredientSkuSnapshot = sku; this.ingredientNameSnapshot = name;
        this.ingredientUnitSnapshot = unit; this.quantity = quantity;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
