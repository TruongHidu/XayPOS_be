package com.possaas.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryAllocationResponse(
        UUID allocationId, UUID orderId, UUID orderItemId, UUID recipeSnapshotId,
        String status, String skipReason, UUID warehouseId,
        Instant reservedAt, Instant consumedAt, Instant releasedAt,
        List<Ingredient> ingredients, boolean directStockSnapshot) {
    public InventoryAllocationResponse(UUID allocationId,UUID orderId,UUID orderItemId,UUID snapshot,String status,String reason,UUID warehouse,Instant reserved,Instant consumed,Instant released,List<Ingredient> ingredients) {this(allocationId,orderId,orderItemId,snapshot,status,reason,warehouse,reserved,consumed,released,ingredients,false);}
    @com.fasterxml.jackson.annotation.JsonProperty public String inventoryMode() {return directStockSnapshot?"DIRECT":null;}
    public InventoryAllocationResponse { ingredients = List.copyOf(ingredients); }

    public record Ingredient(UUID reservationId, UUID recipeUsageId, UUID ingredientId,
            String sku, String name, String unit, BigDecimal quantity, UUID itemId, String itemType) {
        public Ingredient(UUID reservation,UUID usage,UUID ingredient,String sku,String name,String unit,BigDecimal quantity) {this(reservation,usage,ingredient,sku,name,unit,quantity,ingredient,"INGREDIENT");}
    }
}
