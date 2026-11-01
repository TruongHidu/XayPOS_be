package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.AdjustmentDirection;
import com.possaas.modules.inventory.entity.StockDocumentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class StockDocumentRequests {
    private StockDocumentRequests() {}

    public record Create(
            @NotNull StockDocumentType documentType,
            @NotNull UUID warehouseId,
            @Size(max = 2000) String note,
            @Size(max = 100) List<@Valid Line> items) {
        public List<Line> safeItems() { return items == null ? List.of() : items; }
    }

    public record Update(@NotNull Long expectedVersion, @Size(max = 2000) String note) {}

    public record Line(
            UUID ingredientId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
            BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
            AdjustmentDirection adjustmentDirection,
            @Size(max = 1000) String note, UUID itemId) {
        public Line(UUID ingredientId,BigDecimal quantity,BigDecimal unitCost,AdjustmentDirection adjustmentDirection,String note) {this(ingredientId,quantity,unitCost,adjustmentDirection,note,null);}
        public UUID stockItemId() { return stockReference(ingredientId,itemId); }
    }

    public record AddItem(
            @NotNull Long expectedVersion,
            UUID ingredientId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
            BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
            AdjustmentDirection adjustmentDirection,
            @Size(max = 1000) String note, UUID itemId) {
        public AddItem(Long version,UUID legacy,BigDecimal quantity,BigDecimal cost,AdjustmentDirection direction,String note) {this(version,legacy,quantity,cost,direction,note,null);}
        public UUID stockItemId() {return stockReference(ingredientId,itemId);}
    }

    public record UpdateItem(
            @NotNull Long expectedVersion,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
            BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
            AdjustmentDirection adjustmentDirection,
            @Size(max = 1000) String note) {}

    private static UUID stockReference(UUID legacy,UUID item) {
        if((legacy==null && item==null) || (legacy!=null && item!=null && !legacy.equals(item)))
            throw new com.possaas.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_STOCK_ITEM_REFERENCE","Provide itemId or ingredientId with matching values");
        return item!=null?item:legacy;
    }
    public record Transition(@NotNull Long expectedVersion) {}
}
