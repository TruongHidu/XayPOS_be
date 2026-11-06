package com.possaas.modules.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class StockAuditRequests {
    private StockAuditRequests() {}
    public record Create(@NotNull UUID warehouseId,@Size(max=2000) String note) {}
    public record Transition(@NotNull @Min(0) Long expectedVersion) {}
    public record Note(@NotNull @Min(0) Long expectedVersion,@Size(max=2000) String note) {}
    public record CountLine(@NotNull UUID auditItemId,
        @NotNull @DecimalMin("0") @Digits(integer=11,fraction=3) BigDecimal countedQuantity,
        @Size(max=1000) String note) {}
    public record Counts(@NotNull @Min(0) Long expectedVersion,
        @NotNull @Size(min=1,max=100) List<@NotNull @Valid CountLine> items) {}
}
