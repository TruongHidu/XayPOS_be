package com.possaas.modules.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateFeatureRequest(
    @NotBlank @Size(max = 150) String name,
    String description,
    @NotNull Boolean active
) {}
