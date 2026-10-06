package com.possaas.modules.table.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "table_areas")
public class TableArea extends TableRecord {
    @Column(nullable = false, length = 100)
    private String name;
    private String description;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
