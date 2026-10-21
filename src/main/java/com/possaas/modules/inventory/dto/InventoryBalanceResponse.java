package com.possaas.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryBalanceResponse(
        UUID balanceId,
        boolean configured,
        UUID warehouseId,
        String warehouseCode,
        UUID ingredientId,
        String ingredientSku,
        String ingredientName,
        String baseUnit,
        boolean ingredientActive,
        BigDecimal quantity,
        BigDecimal reservedQuantity,
        BigDecimal availableQuantity,
        BigDecimal minQuantity,
        boolean lowStock,
        Long version,
        Instant createdAt,
        Instant updatedAt) {
}
