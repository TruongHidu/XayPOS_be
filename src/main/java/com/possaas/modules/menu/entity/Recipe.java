package com.possaas.modules.menu.entity;

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
@Table(name = "recipes")
public class Recipe {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "menu_item_id", nullable = false)
    private UUID menuItemId;

    @Column(name = "recipe_version", nullable = false)
    private int recipeVersion;

    @Column(name = "yield_quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal yieldQuantity;

    @Column(name = "yield_unit", nullable = false, length = 30)
    private String yieldUnit;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void initialize(UUID restaurantId, UUID menuItemId, int recipeVersion,
            BigDecimal yieldQuantity, String yieldUnit, Instant now) {
        this.restaurantId = restaurantId;
        this.menuItemId = menuItemId;
        this.recipeVersion = recipeVersion;
        this.yieldQuantity = yieldQuantity;
        this.yieldUnit = yieldUnit;
        this.active = true;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = this.createdAt;
    }

    public void deactivate(Instant now) {
        active = false;
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
