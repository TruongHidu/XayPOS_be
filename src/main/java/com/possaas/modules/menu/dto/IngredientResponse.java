package com.possaas.modules.menu.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IngredientResponse(
        UUID id,
        String sku,
        String name,
        String baseUnit,
        String description,
        BigDecimal costPrice,
        boolean trackInventory,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
