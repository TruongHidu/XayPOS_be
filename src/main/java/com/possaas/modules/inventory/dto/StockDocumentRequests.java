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
            @NotNull UUID ingredientId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
            BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
            AdjustmentDirection adjustmentDirection,
            @Size(max = 1000) String note) {}

    public record AddItem(
            @NotNull Long expectedVersion,
            @NotNull UUID ingredientId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
            BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
            AdjustmentDirection adjustmentDirection,
            @Size(max = 1000) String note) {}

    public record UpdateItem(
            @NotNull Long expectedVersion,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3)
            BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitCost,
            AdjustmentDirection adjustmentDirection,
            @Size(max = 1000) String note) {}

    public record Transition(@NotNull Long expectedVersion) {}
}
