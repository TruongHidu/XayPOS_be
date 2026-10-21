package com.possaas.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryAllocationResponse(
        UUID allocationId, UUID orderId, UUID orderItemId, UUID recipeSnapshotId,
        String status, String skipReason, UUID warehouseId,
        Instant reservedAt, Instant consumedAt, Instant releasedAt,
        List<Ingredient> ingredients) {
    public InventoryAllocationResponse { ingredients = List.copyOf(ingredients); }

    public record Ingredient(UUID reservationId, UUID recipeUsageId, UUID ingredientId,
            String sku, String name, String unit, BigDecimal quantity) {}
}
