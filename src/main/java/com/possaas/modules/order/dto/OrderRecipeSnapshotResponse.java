package com.possaas.modules.order.dto;

import com.possaas.modules.order.entity.RecipeSnapshotStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderRecipeSnapshotResponse(UUID orderId, UUID orderItemId, RecipeSnapshotStatus snapshotStatus,
        UUID recipeId, Integer recipeVersion, BigDecimal yieldQuantity, String yieldUnit,
        BigDecimal orderQuantity, String orderUnit, Instant capturedAt, List<Ingredient> ingredients, Boolean recipeEnabledSnapshot,
        com.possaas.modules.order.entity.OrderCostSource costSourceSnapshot) {
    public OrderRecipeSnapshotResponse { ingredients = List.copyOf(ingredients); }
    public record Ingredient(UUID ingredientId, String sku, String name, String unit,
            BigDecimal recipeQuantity, BigDecimal wastePercent, boolean trackInventory, BigDecimal requiredQuantity) {}
}
