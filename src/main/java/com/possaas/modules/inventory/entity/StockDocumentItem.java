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
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "stock_document_items")
public class StockDocumentItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;
    @Column(name = "stock_document_id", nullable = false)
    private UUID stockDocumentId;
    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;
    @Column(name = "ingredient_sku", length = 80)
    private String ingredientSku;
    @Column(name = "ingredient_name", nullable = false, length = 150)
    private String ingredientName;
    @Column(nullable = false, length = 30)
    private String unit;
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;
    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_direction", length = 20)
    private AdjustmentDirection adjustmentDirection;
    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitCost;
    @Column(name = "total_cost", nullable = false, precision = 16, scale = 2)
    private BigDecimal totalCost;
    private String note;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void initialize(UUID restaurantId, UUID documentId, UUID ingredientId, String sku, String name,
            String unit, BigDecimal quantity, AdjustmentDirection direction, BigDecimal unitCost,
            String note, Instant now) {
        Instant timestamp = now.truncatedTo(ChronoUnit.MICROS);
        this.restaurantId = restaurantId;
        this.stockDocumentId = documentId;
        this.ingredientId = ingredientId;
        this.ingredientSku = sku;
        this.ingredientName = name;
        this.unit = unit;
        update(quantity, direction, unitCost, note, timestamp);
        this.createdAt = timestamp;
    }

    public boolean update(BigDecimal quantity, AdjustmentDirection direction, BigDecimal unitCost,
            String note, Instant now) {
        BigDecimal newTotalCost = quantity.multiply(unitCost).setScale(2);
        if (this.quantity != null
                && this.quantity.compareTo(quantity) == 0
                && this.adjustmentDirection == direction
                && this.unitCost.compareTo(unitCost) == 0
                && this.totalCost.compareTo(newTotalCost) == 0
                && Objects.equals(this.note, note)) {
            return false;
        }
        this.quantity = quantity;
        this.adjustmentDirection = direction;
        this.unitCost = unitCost;
        this.totalCost = newTotalCost;
        this.note = note;
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
        return true;
    }
}
