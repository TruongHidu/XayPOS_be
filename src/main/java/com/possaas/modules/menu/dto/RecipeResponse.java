package com.possaas.modules.menu.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecipeResponse(
        UUID id,
        UUID menuItemId,
        int recipeVersion,
        BigDecimal yieldQuantity,
        String yieldUnit,
        boolean active,
        List<Ingredient> ingredients,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public record Ingredient(
            UUID id,
            UUID ingredientId,
            String sku,
            String name,
            String unit,
            BigDecimal quantity,
            BigDecimal wastePercent) {
    }
}
