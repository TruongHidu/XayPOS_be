package com.possaas.modules.menu.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "recipe_items")
public class RecipeItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "recipe_id", nullable = false)
    private UUID recipeId;

    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;

    @Column(name = "ingredient_name_snapshot", nullable = false, length = 150)
    private String ingredientNameSnapshot;

    @Column(name = "ingredient_sku_snapshot", length = 80)
    private String ingredientSkuSnapshot;

    @Column(name = "ingredient_unit_snapshot", nullable = false, length = 30)
    private String ingredientUnitSnapshot;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "waste_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal wastePercent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void initialize(UUID restaurantId, UUID recipeId, Item ingredient,
            BigDecimal quantity, BigDecimal wastePercent, Instant now) {
        this.restaurantId = restaurantId;
        this.recipeId = recipeId;
        this.ingredientId = ingredient.getId();
        this.ingredientNameSnapshot = ingredient.getName();
        this.ingredientSkuSnapshot = ingredient.getSku();
        this.ingredientUnitSnapshot = ingredient.getBaseUnit();
        this.quantity = quantity;
        this.wastePercent = wastePercent;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = this.createdAt;
    }
}
