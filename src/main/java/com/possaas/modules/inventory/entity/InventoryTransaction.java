package com.possaas.modules.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Getter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "inventory_transactions")
public class InventoryTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Column(name = "item_id", nullable = false)
    private UUID itemId;
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private InventoryTransactionType transactionType;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private InventorySourceType sourceType = InventorySourceType.STOCK_DOCUMENT;
    @Column(name = "order_id") private UUID orderId;
    @Column(name = "order_item_id") private UUID orderItemId;
    @Column(name = "reservation_id") private UUID reservationId;
    @Column(name = "recipe_usage_id") private UUID recipeUsageId;
    @Column(name = "stock_document_id")
    private UUID stockDocumentId;
    @Column(name = "stock_document_item_id")
    private UUID stockDocumentItemId;
    @Column(name = "quantity_change", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantityChange;
    @Column(name = "before_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal beforeQuantity;
    @Column(name = "after_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal afterQuantity;
    @Column(nullable = false, length = 30)
    private String unit;
    @Column(name = "unit_cost", precision = 14, scale = 2)
    private BigDecimal unitCost;
    @Column(name = "idempotency_key", nullable = false, length = 150)
    private String idempotencyKey;
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public InventoryTransaction(UUID restaurantId, UUID warehouseId, UUID itemId,
            InventoryTransactionType type, UUID documentId, UUID documentItemId,
            BigDecimal change, BigDecimal before, BigDecimal after, String unit,
            BigDecimal unitCost, String idempotencyKey, UUID createdBy, Instant now) {
        this.restaurantId = restaurantId;
        this.warehouseId = warehouseId;
        this.itemId = itemId;
        this.transactionType = type;
        this.stockDocumentId = documentId;
        this.stockDocumentItemId = documentItemId;
        this.quantityChange = change;
        this.beforeQuantity = before;
        this.afterQuantity = after;
        this.unit = unit;
        this.unitCost = unitCost;
        this.idempotencyKey = idempotencyKey;
        this.createdBy = createdBy;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public static InventoryTransaction recipeUse(OrderItemInventoryAllocation allocation,
            InventoryReservation reservation, BigDecimal before, BigDecimal after, UUID actor, Instant now) {
        InventoryTransaction transaction = new InventoryTransaction(allocation.getRestaurantId(),
                reservation.getWarehouseId(), reservation.getIngredientId(), InventoryTransactionType.RECIPE_USE,
                null, null, reservation.getQuantity().negate(), before, after,
                reservation.getIngredientUnitSnapshot(), null,
                "RECIPE_USE:" + allocation.getOrderItemId() + ":" + reservation.getIngredientId(), actor, now);
        transaction.sourceType = InventorySourceType.ORDER_ITEM;
        transaction.orderId = allocation.getOrderId(); transaction.orderItemId = allocation.getOrderItemId();
        transaction.reservationId = reservation.getId(); transaction.recipeUsageId = reservation.getRecipeUsageId();
        return transaction;
    }
}
