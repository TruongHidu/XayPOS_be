package com.possaas.modules.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
@Table(name = "warehouses")
public class Warehouse {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;
    @Column(nullable = false, length = 50)
    private String code;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "is_default", nullable = false)
    private boolean defaultWarehouse;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public void initialize(UUID restaurantId, String code, String name, boolean defaultWarehouse, Instant now) {
        Instant timestamp = now.truncatedTo(ChronoUnit.MICROS);
        this.restaurantId = restaurantId;
        this.code = code;
        this.name = name;
        this.defaultWarehouse = defaultWarehouse;
        this.active = true;
        this.createdAt = timestamp;
        this.updatedAt = timestamp;
    }

    public void touch(Instant now) {
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void softDelete(Instant now) {
        deletedAt = now.truncatedTo(ChronoUnit.MICROS);
        defaultWarehouse = false;
        active = false;
        touch(now);
    }
}
