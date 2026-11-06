package com.possaas.modules.subscription.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;

public record CreatePackageRequest(
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*")
    String code,
    @NotBlank @Size(max = 100) String name,
    String description,
    @NotNull @DecimalMin("0.0") BigDecimal priceAmount,
    @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
    @Min(1) @Max(120) short billingCycleMonths,
    @Size(max = 200) List<@NotNull @Valid PackageFeatureSelectionRequest> features,
    @JsonDeserialize(using = PackageLimitDeserializer.class) Object maxStaff
) {
    public CreatePackageRequest(String code, String name, String description, BigDecimal priceAmount,
        String currencyCode, short billingCycleMonths) {
        this(code, name, description, priceAmount, currencyCode, billingCycleMonths, null, null);
    }
}
