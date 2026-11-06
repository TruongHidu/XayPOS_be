package com.possaas.modules.qrorder.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @MappedSuperclass
public abstract class QrRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name="restaurant_id", nullable=false, updatable=false) private UUID restaurantId;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    public void initialize(UUID tenant, Instant now) {
        restaurantId=tenant; createdAt=now.truncatedTo(ChronoUnit.MICROS); updatedAt=createdAt;
    }
    public void setUpdatedAt(Instant now) { updatedAt=now.truncatedTo(ChronoUnit.MICROS); }
}
