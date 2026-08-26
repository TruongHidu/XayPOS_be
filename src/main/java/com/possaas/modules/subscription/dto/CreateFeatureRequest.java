package com.possaas.modules.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateFeatureRequest(
    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*")
    String code,
    @NotBlank @Size(max = 150) String name,
    String description
) {}
