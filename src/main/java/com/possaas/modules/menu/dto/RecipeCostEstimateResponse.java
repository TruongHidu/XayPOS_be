package com.possaas.modules.menu.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecipeCostEstimateResponse(
        UUID menuItemId,
        UUID recipeId,
        int recipeVersion,
        boolean recipeActive,
        BigDecimal yieldQuantity,
        String yieldUnit,
        String currencyCode,
        String costSource,
        BigDecimal totalBatchCost,
        BigDecimal costPerYieldUnit,
        List<Ingredient> ingredients,
        List<Warning> warnings,
        Instant estimatedAt) {

    public RecipeCostEstimateResponse {
        ingredients = List.copyOf(ingredients);
        warnings = List.copyOf(warnings);
    }

    public record Ingredient(
            UUID ingredientId,
            String sku,
            String name,
            String unit,
            BigDecimal quantity,
            BigDecimal wastePercent,
            BigDecimal grossQuantity,
            BigDecimal unitCost,
            BigDecimal lineCost) {}

    public record Warning(String code, UUID ingredientId, String message) {}
}
