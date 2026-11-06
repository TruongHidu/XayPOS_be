package com.possaas.modules.menu.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "item_groups")
public class ItemGroup extends MenuRecord {
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
