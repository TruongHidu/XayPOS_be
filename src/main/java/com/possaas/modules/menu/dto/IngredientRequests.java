package com.possaas.modules.menu.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public final class IngredientRequests {
    private IngredientRequests() {
    }

    public record Create(
            @Size(max = 80) String sku,
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(max = 30) String baseUnit,
            @Size(max = 10000) String description,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal costPrice,
            Boolean trackInventory,
            Boolean active, Boolean costConfigured) {
    }

    public record Update(
            @Size(max = 80) String sku,
            @Size(max = 150) String name,
            @Size(max = 30) String baseUnit,
            @Size(max = 10000) String description,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal costPrice,
            Boolean trackInventory,
            @NotNull @Min(0) Long expectedVersion, Boolean costConfigured) {
    }

    public record Status(@NotNull Boolean active, @NotNull @Min(0) Long expectedVersion) {
    }
}
