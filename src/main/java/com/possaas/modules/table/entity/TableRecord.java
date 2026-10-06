package com.possaas.modules.table.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Tenant identity, optimistic version and application-Clock timestamps only. */
@Getter
@Setter
@MappedSuperclass
public abstract class TableRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void initialize(UUID tenantId, Instant now) {
        restaurantId = tenantId;
        createdAt = now.truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    public void setUpdatedAt(Instant now) {
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
