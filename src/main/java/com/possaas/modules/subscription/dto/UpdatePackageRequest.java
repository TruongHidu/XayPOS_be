package com.possaas.modules.subscription.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record UpdatePackageRequest(
    @NotBlank @Size(max = 100) String name,
    String description,
    @NotNull @DecimalMin("0.0") BigDecimal priceAmount,
    @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
    @Min(1) @Max(120) short billingCycleMonths,
    @NotNull Boolean active
) {}
