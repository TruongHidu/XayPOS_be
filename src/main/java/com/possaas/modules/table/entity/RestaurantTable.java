package com.possaas.modules.table.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "restaurant_tables")
public class RestaurantTable extends TableRecord {
    @Column(name = "area_id")
    private UUID areaId;
    @Column(nullable = false, length = 50)
    private String code;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false)
    private short capacity = 4;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TableStatus status = TableStatus.AVAILABLE;
    @Column(name = "qr_token", nullable = false, length = 128)
    private String qrToken;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
