package com.possaas.modules.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class LowStockAlertRequests {
    private LowStockAlertRequests() {}
    public record Acknowledge(@NotNull @Min(0) Long expectedVersion) {}
}
