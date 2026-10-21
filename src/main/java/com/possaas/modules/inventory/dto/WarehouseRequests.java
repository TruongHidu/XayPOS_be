package com.possaas.modules.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class WarehouseRequests {
    private WarehouseRequests() {
    }

    public record Create(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 100) String name) {
    }

    public record Update(
            @Size(max = 50) String code,
            @Size(max = 100) String name,
            @NotNull @Min(0) Long expectedVersion) {
    }

    public record Status(@NotNull Boolean active, @NotNull @Min(0) Long expectedVersion) {
    }

    public record MakeDefault(@NotNull @Min(0) Long expectedVersion) {
    }
}
