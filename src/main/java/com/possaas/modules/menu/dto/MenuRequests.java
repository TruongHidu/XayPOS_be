package com.possaas.modules.menu.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.possaas.modules.menu.entity.AvailabilityStatus;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public final class MenuRequests {
    private MenuRequests() {
    }

    public record CreateGroup(@NotBlank @Size(max = 100) String name, @Min(0) Integer displayOrder, Boolean active) {
    }

    public record UpdateGroup(@Size(max = 100) String name, @Min(0) Integer displayOrder,
            @NotNull @Min(0) Long expectedVersion) {
    }

    public record CreateItem(UUID groupId, @Size(max = 80) String sku, @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(max = 30) String baseUnit, @Size(max = 10000) String description,
            @Size(max = 500) String imageUrl,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal salePrice, Boolean active) {
    }

    public record UpdateItem(@Size(max = 150) String name, @Size(max = 80) String sku, @Size(max = 30) String baseUnit,
            @Size(max = 10000) String description, @Size(max = 500) String imageUrl,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal salePrice,
            @NotNull @Min(0) Long expectedVersion) {
    }

    public record Status(@NotNull Boolean active, @NotNull @Min(0) Long expectedVersion) {
    }

    public record Availability(@NotNull AvailabilityStatus availabilityStatus, @NotNull @Min(0) Long expectedVersion) {
    }

    public record GroupAssignment(@JsonProperty(value = "groupId", required = true) UUID groupId,
            @NotNull @Min(0) Long expectedVersion) {
    }
}
