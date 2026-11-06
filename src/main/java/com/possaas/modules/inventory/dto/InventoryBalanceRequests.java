package com.possaas.modules.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public final class InventoryBalanceRequests {
    private InventoryBalanceRequests() {
    }

    public record UpdateThreshold(
            @Min(0) Long expectedVersion,
            @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal minQuantity) {
    }
}
