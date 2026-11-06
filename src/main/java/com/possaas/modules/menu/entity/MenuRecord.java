package com.possaas.modules.menu.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Menu timestamps are supplied explicitly by the application Clock. */
@Getter
@Setter
@MappedSuperclass
public abstract class MenuRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;
    @Version
    private long version;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public void initialize(UUID tenant, Instant now) {
        restaurantId = tenant;
        createdAt = now.truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    // PostgreSQL timestamps store microseconds. Keep immediate and reloaded responses identical.
    public void setUpdatedAt(Instant now) {
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void setDeletedAt(Instant now) {
        deletedAt = now == null ? null : now.truncatedTo(ChronoUnit.MICROS);
    }
}
