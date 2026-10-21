package com.possaas.modules.menu.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class RecipeRequests {
    private RecipeRequests() {
    }

    public record Ingredient(
            @NotNull UUID ingredientId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
            BigDecimal quantity,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal wastePercent) {
    }

    public record Create(
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
            BigDecimal yieldQuantity,
            @NotNull @Size(min = 1, max = 100) List<@Valid Ingredient> ingredients) {
    }

    public record CreateVersion(
            @NotNull @Min(0) Long expectedVersion,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3)
            BigDecimal yieldQuantity,
            @NotNull @Size(min = 1, max = 100) List<@Valid Ingredient> ingredients) {
    }
}
