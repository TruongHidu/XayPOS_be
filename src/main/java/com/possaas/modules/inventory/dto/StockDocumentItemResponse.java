package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.AdjustmentDirection;
import java.math.BigDecimal;
import java.util.UUID;

public record StockDocumentItemResponse(
        UUID id,
        UUID ingredientId,
        String ingredientSku,
        String ingredientName,
        String unit,
        BigDecimal quantity,
        AdjustmentDirection adjustmentDirection,
        BigDecimal unitCost,
        BigDecimal totalCost,
        String note) {}
