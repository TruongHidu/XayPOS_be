package com.possaas.modules.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record StockAuditItemResponse(UUID id,UUID ingredientId,String sku,String name,String unit,
        BigDecimal systemQuantity,BigDecimal countedQuantity,BigDecimal differenceQuantity,
        BigDecimal referenceUnitCostSnapshot,String note, UUID itemId, String itemType) {
    public StockAuditItemResponse(UUID id,UUID ingredientId,String sku,String name,String unit,
        BigDecimal systemQuantity,BigDecimal countedQuantity,BigDecimal differenceQuantity,
        BigDecimal referenceUnitCostSnapshot,String note) {this(id,ingredientId,sku,name,unit,systemQuantity,countedQuantity,differenceQuantity,referenceUnitCostSnapshot,note, ingredientId,"INGREDIENT");}
}
