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

@Getter @Entity @Immutable @Table(name = "order_item_recipe_snapshots")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemRecipeSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "order_item_id", nullable = false, updatable = false) private UUID orderItemId;
    @Column(name = "menu_item_id_snapshot", updatable = false) private UUID menuItemIdSnapshot;
    @Enumerated(EnumType.STRING) @Column(name = "snapshot_status", nullable = false, length = 30, updatable = false)
    private RecipeSnapshotStatus snapshotStatus;
    @Column(name = "recipe_enabled_snapshot", updatable = false) private Boolean recipeEnabledSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_source_snapshot", length = 20, updatable = false) private OrderCostSource costSourceSnapshot;
    @Column(name = "recipe_id", updatable = false) private UUID recipeId;
    @Column(name = "recipe_version", updatable = false) private Integer recipeVersion;
    @Column(name = "yield_quantity", precision = 12, scale = 3, updatable = false) private BigDecimal yieldQuantity;
    @Column(name = "yield_unit", length = 30, updatable = false) private String yieldUnit;
    @Column(name = "order_quantity_snapshot", nullable = false, precision = 12, scale = 3, updatable = false)
    private BigDecimal orderQuantitySnapshot;
    @Column(name = "order_unit_snapshot", nullable = false, length = 30, updatable = false) private String orderUnitSnapshot;
    @Column(name = "captured_at", updatable = false) private Instant capturedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    @Column(name="cost_snapshot_version",updatable=false) private Short costSnapshotVersion;
    @Enumerated(EnumType.STRING) @Column(name="cost_status",length=30,updatable=false) private OrderCostStatus costStatus;
    @Enumerated(EnumType.STRING) @Column(name="cost_unavailable_reason",length=60,updatable=false) private OrderCostUnavailableReason costUnavailableReason;
    @Column(name="unit_cost_snapshot",precision=38,scale=2,updatable=false) private BigDecimal unitCostSnapshot;
    @Column(name="total_cost_snapshot",precision=38,scale=2,updatable=false) private BigDecimal totalCostSnapshot;

    public OrderItemRecipeSnapshot(OrderItem line, RecipeSnapshotStatus status, UUID recipeId,
            Integer recipeVersion, BigDecimal yieldQuantity, String yieldUnit, Instant now, Boolean recipeEnabled, OrderCostSnapshot cost) {
        java.util.Objects.requireNonNull(cost,"Explicit monetary decision is required");
        this.costSnapshotVersion=cost.version(); this.costStatus=cost.status(); this.costUnavailableReason=cost.reason();
        this.unitCostSnapshot=cost.unitCost(); this.totalCostSnapshot=cost.totalCost();
        this.recipeEnabledSnapshot = recipeEnabled;
        this.costSourceSnapshot = recipeEnabled == null ? null : recipeEnabled ? OrderCostSource.RECIPE : OrderCostSource.MANUAL;
        this.restaurantId = line.getRestaurantId(); this.orderItemId = line.getId();
        this.menuItemIdSnapshot = line.getItemId(); this.snapshotStatus = status;
        this.recipeId = recipeId; this.recipeVersion = recipeVersion;
        this.yieldQuantity = yieldQuantity; this.yieldUnit = yieldUnit;
        this.orderQuantitySnapshot = line.getQuantity(); this.orderUnitSnapshot = line.getUnit();
        this.capturedAt = now.truncatedTo(ChronoUnit.MICROS); this.createdAt = this.capturedAt;
    }
}
