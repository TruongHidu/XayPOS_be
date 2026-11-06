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
        String note, UUID itemId, String itemType) {
    public StockDocumentItemResponse(UUID id,UUID ingredientId,String sku,String name,String unit,BigDecimal quantity,AdjustmentDirection direction,BigDecimal cost,BigDecimal total,String note) {this(id,ingredientId,sku,name,unit,quantity,direction,cost,total,note,ingredientId,"INGREDIENT");}
}
