package com.possaas.modules.menu.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RecipeSummaryResponse(
        UUID id,
        UUID menuItemId,
        int recipeVersion,
        BigDecimal yieldQuantity,
        String yieldUnit,
        boolean active,
        long ingredientCount,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
