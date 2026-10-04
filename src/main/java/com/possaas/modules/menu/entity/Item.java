package com.possaas.modules.menu.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "items")
public class Item extends MenuRecord {
    @Column(name = "item_group_id")
    private UUID groupId;
    @Column(length = 80)
    private String sku;
    @Column(nullable = false, length = 150)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    private ItemType itemType;
    @Column(name = "base_unit", nullable = false, length = 30)
    private String baseUnit;
    private String description;
    @Column(name = "image_url", length = 500)
    private String imageUrl;
    @Column(name = "sale_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal salePrice;
    @Column(name = "cost_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal costPrice = BigDecimal.ZERO;
    @Column(name = "track_inventory", nullable = false)
    private boolean trackInventory;
    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", nullable = false, length = 20)
    private AvailabilityStatus availabilityStatus = AvailabilityStatus.AVAILABLE;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
