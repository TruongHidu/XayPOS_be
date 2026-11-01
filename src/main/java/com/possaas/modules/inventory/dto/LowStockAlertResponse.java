package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.LowStockAlertStatus;
import com.possaas.modules.inventory.entity.LowStockAlertResolutionReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LowStockAlertResponse(UUID id,UUID warehouseId,String warehouseCode,String warehouseName,
        UUID ingredientId,String ingredientSku,String ingredientName,String unit,LowStockAlertStatus status,
        BigDecimal quantityAtTrigger,BigDecimal thresholdQuantity,BigDecimal currentQuantity,
        BigDecimal reservedQuantity,BigDecimal availableQuantity,BigDecimal currentMinQuantity,
        Instant triggeredAt,UUID acknowledgedBy,Instant acknowledgedAt,Instant resolvedAt,
        LowStockAlertResolutionReason resolutionReason,long version,Instant createdAt,Instant updatedAt, UUID itemId, String itemType) {
    public LowStockAlertResponse(UUID id,UUID warehouseId,String warehouseCode,String warehouseName,
        UUID ingredientId,String ingredientSku,String ingredientName,String unit,LowStockAlertStatus status,
        BigDecimal quantityAtTrigger,BigDecimal thresholdQuantity,BigDecimal currentQuantity,
        BigDecimal reservedQuantity,BigDecimal availableQuantity,BigDecimal currentMinQuantity,
        Instant triggeredAt,UUID acknowledgedBy,Instant acknowledgedAt,Instant resolvedAt,
        LowStockAlertResolutionReason resolutionReason,long version,Instant createdAt,Instant updatedAt) {this(id,warehouseId,warehouseCode,warehouseName,ingredientId,ingredientSku,ingredientName,unit,status,quantityAtTrigger,thresholdQuantity,currentQuantity,reservedQuantity,availableQuantity,currentMinQuantity,triggeredAt,acknowledgedBy,acknowledgedAt,resolvedAt,resolutionReason,version,createdAt,updatedAt, ingredientId,"INGREDIENT");}
}
