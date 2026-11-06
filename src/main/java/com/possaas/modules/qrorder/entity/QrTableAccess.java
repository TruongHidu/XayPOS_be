package com.possaas.modules.qrorder.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Access generation is separate from physical-table optimistic editing version. */
@Getter @Setter @Entity @Table(name="qr_table_access")
public class QrTableAccess {
    @Id @Column(name="table_id") private UUID tableId;
    @Column(name="restaurant_id",nullable=false,updatable=false) private UUID restaurantId;
    @Column(nullable=false) private long epoch;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
