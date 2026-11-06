package com.possaas.modules.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "inventory_balances")
public class InventoryBalance {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Column(name = "item_id", nullable = false)
    private UUID itemId;
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity = BigDecimal.ZERO.setScale(3);
    @Column(name = "reserved_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal reservedQuantity = BigDecimal.ZERO.setScale(3);
    @Column(name = "min_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal minQuantity = BigDecimal.ZERO.setScale(3);
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void initialize(UUID restaurantId, UUID warehouseId, UUID itemId, BigDecimal minQuantity, Instant now) {
        Instant timestamp = now.truncatedTo(ChronoUnit.MICROS);
        this.restaurantId = restaurantId;
        this.warehouseId = warehouseId;
        this.itemId = itemId;
        this.quantity = BigDecimal.ZERO.setScale(3);
        this.reservedQuantity = BigDecimal.ZERO.setScale(3);
        this.minQuantity = minQuantity;
        this.createdAt = timestamp;
        this.updatedAt = timestamp;
    }

    public void touch(Instant now) {
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void applyQuantityChange(BigDecimal change, Instant now) {
        quantity = quantity.add(change).setScale(3);
        touch(now);
    }

    public BigDecimal getAvailableQuantity() {
        return quantity.subtract(reservedQuantity).setScale(3);
    }

    public void applyReservationChange(BigDecimal change, Instant now) {
        reservedQuantity = reservedQuantity.add(change).setScale(3);
        touch(now);
    }
}
