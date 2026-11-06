package com.possaas.modules.order.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Getter @Entity @Immutable @Table(name = "order_item_recipe_usages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemRecipeUsage {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "recipe_snapshot_id", nullable = false, updatable = false) private UUID recipeSnapshotId;
    @Column(name = "ingredient_id", nullable = false, updatable = false) private UUID ingredientId;
    @Column(name = "ingredient_name_snapshot", nullable = false, length = 150, updatable = false) private String ingredientNameSnapshot;
    @Column(name = "ingredient_sku_snapshot", length = 80, updatable = false) private String ingredientSkuSnapshot;
    @Column(name = "ingredient_unit_snapshot", nullable = false, length = 30, updatable = false) private String ingredientUnitSnapshot;
    @Column(name = "recipe_quantity_snapshot", nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal recipeQuantitySnapshot;
    @Column(name = "waste_percent_snapshot", nullable = false, precision = 5, scale = 2, updatable = false) private BigDecimal wastePercentSnapshot;
    @Column(name = "track_inventory_snapshot", nullable = false, updatable = false) private boolean trackInventorySnapshot;
    @Column(name = "required_quantity", nullable = false, precision = 14, scale = 3, updatable = false) private BigDecimal requiredQuantity;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    @Column(name="cost_configured_snapshot",updatable=false) private Boolean costConfiguredSnapshot;
    @Column(name="unit_cost_snapshot",precision=14,scale=2,updatable=false) private BigDecimal unitCostSnapshot;
    @Column(name="line_cost_snapshot",precision=38,scale=2,updatable=false) private BigDecimal lineCostSnapshot;

    public OrderItemRecipeUsage(UUID tenant, UUID snapshotId, UUID ingredientId, String name, String sku,
            String unit, BigDecimal recipeQuantity, BigDecimal waste, boolean tracked, BigDecimal required, Instant now, OrderIngredientCostSnapshot cost) {
        java.util.Objects.requireNonNull(cost,"Explicit ingredient monetary decision is required");
        this.costConfiguredSnapshot=cost.configured();this.unitCostSnapshot=cost.unitCost();this.lineCostSnapshot=cost.lineCost();
        this.restaurantId = tenant; this.recipeSnapshotId = snapshotId; this.ingredientId = ingredientId;
        this.ingredientNameSnapshot = name; this.ingredientSkuSnapshot = sku; this.ingredientUnitSnapshot = unit;
        this.recipeQuantitySnapshot = recipeQuantity; this.wastePercentSnapshot = waste;
        this.trackInventorySnapshot = tracked; this.requiredQuantity = required;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
