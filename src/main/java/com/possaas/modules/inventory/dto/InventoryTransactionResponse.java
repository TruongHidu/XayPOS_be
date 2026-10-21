package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.InventoryTransactionType;
import com.possaas.modules.inventory.entity.InventorySourceType;
import com.possaas.modules.inventory.entity.StockDocumentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryTransactionResponse(
        UUID transactionId,
        UUID warehouseId,
        String warehouseCode,
        UUID ingredientId,
        String ingredientSku,
        String ingredientName,
        String unit,
        UUID stockDocumentId,
        String documentCode,
        StockDocumentType documentType,
        InventoryTransactionType transactionType,
        BigDecimal quantityChange,
        BigDecimal beforeQuantity,
        BigDecimal afterQuantity,
        BigDecimal unitCost,
        UUID createdBy,
        Instant createdAt,
        InventorySourceType sourceType,
        UUID orderId,
        UUID orderItemId,
        UUID reservationId,
        UUID recipeUsageId) {}
